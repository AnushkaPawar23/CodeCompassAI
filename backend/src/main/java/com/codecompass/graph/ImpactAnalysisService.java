package com.codecompass.graph;

import com.codecompass.llm.GroqChatService;
import com.codecompass.rag.CodeChunk;
import com.codecompass.rag.CodeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stage 6 — change-impact analysis service.
 *
 * <h2>Purpose</h2>
 * <p>Given a target method {@code (repoId, targetClass, targetMethod)}, this
 * service answers the question: <em>"If I change this method, what else could
 * break?"</em>.  It combines the static call-graph traversal from
 * {@link GraphTraversalService} with an LLM-generated natural-language
 * explanation of which dependent methods are affected and why.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Graph traversal</b> — delegates to
 *       {@link GraphTraversalService#findTransitiveDependents} to obtain the
 *       full transitive caller set (BFS, all depths).</li>
 *   <li><b>Zero-dependents fast path</b> — if no callers are found the method
 *       is an entry point or leaf; return immediately with a "safe to change"
 *       message and skip the LLM call.</li>
 *   <li><b>Code chunk lookup</b> — for the target method and for each
 *       dependent, fetches the corresponding {@link CodeChunk} from
 *       {@link CodeChunkRepository}.  Primary lookup is by
 *       {@code (repoId, className, methodName)} which returns the
 *       {@code METHOD}-type chunk.  If no method chunk exists, falls back
 *       to the {@code CLASS}-type chunk so the prompt still has code context.</li>
 *   <li><b>Prompt construction</b> — builds a structured prompt that includes
 *       the target method's source code and each dependent's source code,
 *       each capped at {@code codecompass.rag.max-chunk-chars} characters to
 *       avoid Groq 413 errors (same guard as {@link com.codecompass.rag.RagService}).</li>
 *   <li><b>LLM call</b> — delegates to {@link GroqChatService#chat(String)}.</li>
 *   <li><b>Return</b> — packages the dependent list and LLM explanation into
 *       an {@link ImpactResult} for the controller.</li>
 * </ol>
 *
 * <h2>Context-overflow safety</h2>
 * <p>Each chunk's content is truncated to {@code codecompass.rag.max-chunk-chars}
 * characters (default 2 000, ~500 tokens) before inclusion in the prompt — the
 * same limit applied in {@link com.codecompass.rag.RagService#buildPrompt}.
 * The number of dependents included in the prompt is additionally capped at
 * {@code codecompass.impact.max-dependents-in-prompt} (default 10) to keep
 * the total prompt size bounded for repos with many callers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImpactAnalysisService {

    private final GraphTraversalService traversalService;
    private final CodeChunkRepository   chunkRepository;
    private final GroqChatService       groqChatService;

    /**
     * Per-chunk character cap — reuses the Stage 4 property so a single
     * config value controls context size across both RAG and impact analysis.
     */
    @Value("${codecompass.rag.max-chunk-chars:2000}")
    private int maxChunkChars;

    /**
     * Maximum number of dependents whose code is included in the LLM prompt.
     * Dependents beyond this limit are still listed in the response but their
     * code is omitted from the prompt (the LLM still knows they exist via the
     * summary header).
     */
    @Value("${codecompass.impact.max-dependents-in-prompt:10}")
    private int maxDependentsInPrompt;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Immutable result of a change-impact analysis run.
     *
     * @param targetClass      simple class name of the method under analysis
     * @param targetMethod     simple method name of the method under analysis
     * @param repoId           repository scope
     * @param dependentsFound  total number of transitive callers found
     * @param dependents       ordered list of dependent methods (BFS order,
     *                         depth ascending); empty if none found
     * @param explanation      LLM-generated natural-language explanation of
     *                         the potential impact; a "safe to change" message
     *                         if {@code dependentsFound == 0}
     */
    public record ImpactResult(
            String targetClass,
            String targetMethod,
            String repoId,
            int dependentsFound,
            List<GraphTraversalService.DependentMethod> dependents,
            String explanation
    ) {}

    /**
     * Analyses the change impact for the specified method.
     *
     * @param repoId       logical repository identifier
     * @param targetClass  simple class name of the method being changed
     * @param targetMethod simple method name being changed
     * @return {@link ImpactResult} with dependent list and LLM explanation
     */
    public ImpactResult analyse(String repoId, String targetClass, String targetMethod) {

        log.info("Impact analysis — repoId='{}', target={}.{}", repoId, targetClass, targetMethod);

        // ── 1. Traverse the call graph ────────────────────────────────────────
        List<GraphTraversalService.DependentMethod> dependents =
                traversalService.findTransitiveDependents(repoId, targetClass, targetMethod);

        // ── 2. Zero-dependents fast path ──────────────────────────────────────
        if (dependents.isEmpty()) {
            log.info("Impact analysis — {}.{} has no dependents (safe to change in isolation)",
                    targetClass, targetMethod);
            return new ImpactResult(
                    targetClass,
                    targetMethod,
                    repoId,
                    0,
                    List.of(),
                    "No dependents found — %s#%s is not called by any other method in the ".formatted(
                            targetClass, targetMethod) +
                    "ingested codebase. It can be changed in isolation without risk of " +
                    "breaking transitive callers."
            );
        }

        log.info("Impact analysis — {}.{} has {} transitive dependent(s); building LLM prompt",
                targetClass, targetMethod, dependents.size());

        // ── 3. Look up code chunks ────────────────────────────────────────────
        String targetContent = resolveChunkContent(repoId, targetClass, targetMethod);

        // Build prompt only for up to maxDependentsInPrompt dependents
        List<GraphTraversalService.DependentMethod> promptDependents =
                dependents.size() <= maxDependentsInPrompt
                        ? dependents
                        : dependents.subList(0, maxDependentsInPrompt);

        // ── 4. Build the prompt ───────────────────────────────────────────────
        String prompt = buildImpactPrompt(
                targetClass, targetMethod, targetContent,
                promptDependents, dependents.size(), repoId);

        log.debug("Impact prompt built — {} chars, {} dependents in prompt (of {} total)",
                prompt.length(), promptDependents.size(), dependents.size());

        // ── 5. Call Groq ──────────────────────────────────────────────────────
        String explanation = groqChatService.chat(prompt);
        log.info("Impact analysis complete — LLM explanation {} chars", 
                explanation == null ? 0 : explanation.length());

        return new ImpactResult(targetClass, targetMethod, repoId,
                dependents.size(), dependents, explanation);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fetches the source code content for a given method from the chunk store.
     *
     * <p>Lookup strategy (in priority order):
     * <ol>
     *   <li>Find a {@code METHOD}-type chunk matching
     *       {@code (repoId, className, methodName)}.</li>
     *   <li>If not found, fall back to the first {@code CLASS}-type chunk
     *       matching {@code (repoId, className)} — gives at least some context.</li>
     *   <li>If neither exists, return a "(source not available)" placeholder.</li>
     * </ol>
     *
     * <p>The returned content is truncated to {@code maxChunkChars} characters.
     */
    private String resolveChunkContent(String repoId, String className, String methodName) {
        // Primary: METHOD chunk
        List<CodeChunk> methodChunks =
                chunkRepository.findByRepoIdAndClassNameAndMethodName(repoId, className, methodName);
        if (!methodChunks.isEmpty()) {
            return truncate(methodChunks.get(0).getContent());
        }

        // Fallback: CLASS chunk
        List<CodeChunk> classChunks = chunkRepository.findByRepoIdAndClassName(repoId, className);
        for (CodeChunk c : classChunks) {
            if (c.getChunkType() == CodeChunk.ChunkType.CLASS) {
                return truncate(c.getContent());
            }
        }

        log.warn("No code chunk found for {}.{} in repoId='{}' — prompt will note unavailability",
                className, methodName, repoId);
        return "(source code not available in chunk store for " + className + "#" + methodName + ")";
    }

    /**
     * Assembles the structured change-impact prompt sent to Groq.
     *
     * <p>The prompt includes:
     * <ul>
     *   <li>System persona — expert Java code reviewer performing change-impact analysis.</li>
     *   <li>Target method section — class name, method name, and source code.</li>
     *   <li>Dependents section — for each dependent (up to {@code maxDependentsInPrompt}):
     *       class name, method name, file path, BFS depth, and source code.</li>
     *   <li>Task instruction — ask the LLM to explain what could break, citing
     *       specific methods by class name, method name, file, and line range.</li>
     * </ul>
     */
    private String buildImpactPrompt(
            String targetClass,
            String targetMethod,
            String targetContent,
            List<GraphTraversalService.DependentMethod> dependents,
            int totalDependents,
            String repoId) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert Java code reviewer performing change-impact analysis. \
                Your task is to explain, in clear natural language, what could break if \
                the specified target method is changed — and why. Reference each affected \
                method by its exact class name, method name, source file, and line range \
                so the developer can navigate directly to it. Be concise but thorough.

                """);

        // ── Target method ─────────────────────────────────────────────────────
        sb.append("=== TARGET METHOD (being changed) ===\n\n");
        sb.append("Class  : ").append(targetClass).append('\n');
        sb.append("Method : ").append(targetMethod).append('\n');
        sb.append("```java\n");
        sb.append(targetContent);
        sb.append("\n```\n\n");

        // ── Dependents ────────────────────────────────────────────────────────
        sb.append("=== DEPENDENT METHODS (callers — direct and transitive) ===\n\n");

        if (totalDependents > dependents.size()) {
            sb.append("Note: ").append(totalDependents)
              .append(" total dependents found; showing code for the first ")
              .append(dependents.size()).append(" (shallowest callers first).\n\n");
        }

        for (int i = 0; i < dependents.size(); i++) {
            GraphTraversalService.DependentMethod dep = dependents.get(i);
            String depContent = resolveChunkContent(repoId, dep.callerClass(), dep.callerMethod());

            sb.append("[Dependent ").append(i + 1).append(']').append('\n');
            sb.append("Class      : ").append(dep.callerClass()).append('\n');
            sb.append("Method     : ").append(dep.callerMethod()).append('\n');
            sb.append("File       : ").append(dep.callerFile()).append('\n');
            sb.append("Lines      : ").append(dep.callerStartLine())
              .append('–').append(dep.callerEndLine()).append('\n');
            sb.append("Depth      : ").append(dep.depth())
              .append(dep.depth() == 1 ? " (direct caller)" : " (transitive caller)").append('\n');
            sb.append("```java\n");
            sb.append(depContent);
            sb.append("\n```\n\n");
        }

        // ── Task ──────────────────────────────────────────────────────────────
        sb.append("=== TASK ===\n\n");
        sb.append("Explain what could break if ").append(targetClass).append('#')
          .append(targetMethod).append(" is changed. For each dependent method listed above, ");
        sb.append("describe specifically how it might be affected (e.g. contract violations, ");
        sb.append("incorrect data, runtime exceptions, or cascading failures). ");
        sb.append("If some dependents are at depth > 1 (transitive callers), explain the ");
        sb.append("propagation path. Use method names and file locations in your explanation.\n");

        return sb.toString();
    }

    /** Truncates {@code content} to {@code maxChunkChars} characters. */
    private String truncate(String content) {
        if (content == null) return "";
        if (content.length() <= maxChunkChars) return content;
        return content.substring(0, maxChunkChars) + "\n… [truncated]";
    }
}
