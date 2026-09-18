package com.codecompass.rag;

import com.codecompass.api.QaResponse;
import com.codecompass.api.QaResponse.SourceReference;
import com.codecompass.llm.GroqChatService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stage 4 — RAG orchestrator: embed → retrieve → prompt → answer.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><b>Embed the question</b> — calls Ollama {@code nomic-embed-text} via the
 *       shared {@link EmbeddingModel} bean (same model used during ingestion).</li>
 *   <li><b>Retrieve top-K chunks</b> — calls
 *       {@link CodeChunkRepository#findSimilar(float[], int)} which uses pgvector's
 *       cosine-distance operator ({@code &lt;=&gt;}) to find the nearest stored vectors.</li>
 *   <li><b>Truncate chunk content</b> — each chunk's {@code content} field is capped at
 *       {@code codecompass.rag.max-chunk-chars} characters (default 2000, ~500 tokens)
 *       before being inserted into the prompt. This prevents 413 errors from the Groq API
 *       when {@code topK >= 3}.</li>
 *   <li><b>Build the prompt</b> — assembles a structured prompt that includes each
 *       retrieved chunk with its file path, class/method name, and line range so
 *       the LLM can cite exact code locations in its answer.</li>
 *   <li><b>Call Groq ({@code llama-3.3-70b-versatile})</b> — delegates to
 *       {@link GroqChatService#chat(String)}.</li>
 *   <li><b>Return</b> — packages the LLM answer and a {@link SourceReference} list
 *       into a {@link QaResponse} for the controller.</li>
 * </ol>
 *
 * <h2>Top-K configuration</h2>
 * <p>The number of chunks retrieved is controlled by three values (in priority order):
 * <ol>
 *   <li>Per-request {@code topK} field in {@link com.codecompass.api.QaRequest}
 *       (if non-null and positive).</li>
 *   <li>{@code codecompass.rag.default-top-k} config property (default 5).</li>
 *   <li>Hard-capped by {@code codecompass.rag.max-results} config property (default 10).</li>
 * </ol>
 *
 * <h2>repoId scoping</h2>
 * <p>The similarity search ({@link CodeChunkRepository#findSimilar}) currently searches
 * <em>all</em> chunks regardless of {@code repoId}. A scoped variant will be added in
 * Stage 5 when multi-repo support is introduced.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final CodeChunkRepository chunkRepository;
    private final GroqChatService groqChatService;

    @Value("${codecompass.rag.default-top-k:5}")
    private int defaultTopK;

    @Value("${codecompass.rag.max-results:10}")
    private int maxResults;

    /**
     * Maximum characters of chunk content included in the prompt per chunk.
     * Approximates a ~500-token cap (at ~4 chars/token).
     * Configurable via {@code codecompass.rag.max-chunk-chars} (default 2000).
     */
    @Value("${codecompass.rag.max-chunk-chars:2000}")
    private int maxChunkChars;

    /**
     * Answers a plain-English question about the ingested codebase.
     *
     * @param question the user's question
     * @param topK     optional override for number of chunks to retrieve;
     *                 {@code null} falls back to {@code codecompass.rag.default-top-k}
     * @return a {@link QaResponse} containing the LLM answer and source references
     */
    public QaResponse answer(String question, Integer topK) {

        // ── 1. Resolve effective top-K ────────────────────────────────────────
        int k = resolveTopK(topK);
        log.info("RAG query — question='{}', topK={}", abbreviate(question, 80), k);

        // ── 2. Embed the question ─────────────────────────────────────────────
        Response<Embedding> embResponse = embeddingModel.embed(question);
        float[] queryVector = embResponse.content().vector();
        log.debug("Question embedded — {} dimensions", queryVector.length);

        // ── 3. Retrieve top-K similar chunks ──────────────────────────────────
        List<CodeChunk> chunks = chunkRepository.findSimilar(queryVector, k);
        log.info("Retrieved {} chunk(s) from vector store", chunks.size());

        if (chunks.isEmpty()) {
            log.warn("No chunks found for question='{}' — returning empty answer", question);
            return new QaResponse(
                    "No relevant code was found in the ingested codebase for this question. " +
                    "Please ensure the repository has been ingested via POST /api/ingest first.",
                    List.of()
            );
        }

        // ── 4. Build the prompt (with per-chunk content truncation) ───────────
        String prompt = buildPrompt(question, chunks, maxChunkChars);
        log.debug("Prompt built — {} chars, {} sources (max-chunk-chars={})",
                  prompt.length(), chunks.size(), maxChunkChars);

        // ── 5. Call Groq ──────────────────────────────────────────────────────
        String answer = groqChatService.chat(prompt);
        log.info("Groq answered — {} chars", answer == null ? 0 : answer.length());

        // ── 6. Build source references ────────────────────────────────────────
        List<SourceReference> sources = chunks.stream()
                .map(c -> new SourceReference(
                        c.getFilePath(),
                        c.getClassName(),
                        c.getMethodName(),
                        c.getChunkType().name(),
                        c.getStartLine(),
                        c.getEndLine()
                ))
                .toList();

        return new QaResponse(answer, sources);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the effective top-K, applying the per-request override and
     * clamping to the configured ceiling.
     */
    private int resolveTopK(Integer requested) {
        int k = (requested != null && requested > 0) ? requested : defaultTopK;
        return Math.min(k, maxResults);
    }

    /**
     * Assembles the structured RAG prompt sent to Groq.
     *
     * <p>Each retrieved chunk is presented with its provenance header
     * (source number, file path, class/method name, line range) followed
     * by the raw code in a fenced block, so the model can cite specific
     * locations in its answer.
     *
     * <p>Each chunk's {@code content} is truncated to {@code maxChunkChars}
     * characters before insertion to prevent 413 "request too large" errors
     * from the Groq API, particularly when {@code topK >= 3}.
     *
     * @param question      the user's question
     * @param chunks        retrieved code chunks (already ranked by similarity)
     * @param maxChunkChars per-chunk character cap; content beyond this is replaced
     *                      with a {@code \n… [truncated]} suffix
     */
    private static String buildPrompt(String question, List<CodeChunk> chunks, int maxChunkChars) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert code understanding assistant. Your job is to answer \
                questions about a Java codebase based solely on the code context provided \
                below. Be precise and specific. When referencing code, always cite the \
                source file and method name so the developer can navigate directly to it.

                """);

        sb.append("=== CODE CONTEXT ===\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk c = chunks.get(i);
            sb.append("[Source ").append(i + 1).append("]\n");
            sb.append("File      : ").append(c.getFilePath()).append('\n');
            sb.append("Class     : ").append(c.getClassName()).append('\n');
            if (c.getMethodName() != null) {
                sb.append("Method    : ").append(c.getMethodName()).append('\n');
            }
            sb.append("Type      : ").append(c.getChunkType()).append('\n');
            sb.append("Lines     : ").append(c.getStartLine())
              .append("–").append(c.getEndLine()).append('\n');
            sb.append("```java\n");
            sb.append(truncateContent(c.getContent(), maxChunkChars));
            sb.append("\n```\n\n");
        }

        sb.append("=== QUESTION ===\n\n");
        sb.append(question).append('\n');

        return sb.toString();
    }

    /** Returns the first {@code maxLen} characters of {@code s}, appending "…" if truncated. */
    private static String abbreviate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    /**
     * Truncates {@code content} to at most {@code maxChars} characters.
     * Appends {@code \n… [truncated]} when the content exceeds the limit so
     * the LLM knows the snippet is intentionally incomplete.
     *
     * @param content  raw chunk content (may be {@code null})
     * @param maxChars maximum allowed characters
     * @return the (possibly truncated) content string
     */
    private static String truncateContent(String content, int maxChars) {
        if (content == null) return "";
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n… [truncated]";
    }
}
