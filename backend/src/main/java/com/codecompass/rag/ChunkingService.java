package com.codecompass.rag;

import com.codecompass.parsing.ParsedClass;
import com.codecompass.parsing.ParsedFile;
import com.codecompass.parsing.ParsedMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 — converts the structured parse tree produced by Stage 2 into a flat
 * list of {@link CodeChunk} objects ready for embedding.
 *
 * <h2>Chunking strategy</h2>
 * <ul>
 *   <li><b>CLASS chunk</b> — one per {@link ParsedClass}: contains the full
 *       source text of the type declaration (annotations + class body).
 *       Useful for high-level retrieval ("what does this class do?").</li>
 *   <li><b>METHOD chunk</b> — one per {@link ParsedMethod} within each class:
 *       contains the full source text of the method declaration.
 *       Useful for fine-grained retrieval ("which method handles X?").</li>
 * </ul>
 *
 * <p><b>Test files are skipped</b> (configurable via constructor flag —
 * currently hardcoded to {@code true} as agreed for Stage 3).  Test files
 * provide little value for the core RAG pipeline and would inflate embedding
 * costs without benefit.
 *
 * <p>Chunks are returned <em>without</em> embeddings; the {@link EmbeddingService}
 * is responsible for populating the {@code embedding} field and persisting the
 * results.
 */
@Service
@Slf4j
public class ChunkingService {

    /**
     * Converts a list of parsed files into a flat list of un-embedded
     * {@link CodeChunk} objects.
     *
     * <p>Test files (where {@code parsedFile.testFile() == true}) are silently
     * skipped.  Files with no classes, or classes with empty {@code bodyText},
     * are also skipped with a debug-level log.
     *
     * @param parsedFiles the parse results from Stage 2
     * @param repoId      logical repository identifier (source path or Git URL)
     *                    embedded into every chunk for provenance
     * @return ordered list of chunks (CLASS chunks before METHOD chunks, per file)
     */
    public List<CodeChunk> chunk(List<ParsedFile> parsedFiles, String repoId) {
        List<CodeChunk> chunks = new ArrayList<>();

        for (ParsedFile file : parsedFiles) {

            // ── Skip test files ───────────────────────────────────────────
            if (file.testFile()) {
                log.debug("Skipping test file: {}", file.filePath());
                continue;
            }

            for (ParsedClass parsedClass : file.classes()) {
                chunkClass(parsedClass, file.filePath(), repoId, chunks);
            }
        }

        log.info("Chunking complete — {} chunk(s) produced from {} file(s) (test files excluded)",
                chunks.size(), parsedFiles.size());
        return chunks;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Produces one CLASS chunk for the type declaration, then one METHOD chunk
     * for each method within it, appending all to {@code chunks}.
     */
    private void chunkClass(ParsedClass parsedClass,
                             String filePath,
                             String repoId,
                             List<CodeChunk> chunks) {

        // ── Class-level chunk ─────────────────────────────────────────────
        String classBody = parsedClass.bodyText();
        if (classBody == null || classBody.isBlank()) {
            log.debug("Skipping class '{}' — empty bodyText", parsedClass.name());
        } else {
            chunks.add(CodeChunk.builder()
                    .repoId(repoId)
                    .filePath(filePath)
                    .chunkType(CodeChunk.ChunkType.CLASS)
                    .className(parsedClass.name())
                    .methodName(null)
                    .content(classBody)
                    .startLine(parsedClass.startLine())
                    .endLine(parsedClass.endLine())
                    .build());
        }

        // ── Method-level chunks ───────────────────────────────────────────
        for (ParsedMethod method : parsedClass.methods()) {
            String methodBody = method.bodyText();
            if (methodBody == null || methodBody.isBlank()) {
                log.debug("Skipping method '{}#{}' — empty bodyText",
                        parsedClass.name(), method.name());
                continue;
            }

            chunks.add(CodeChunk.builder()
                    .repoId(repoId)
                    .filePath(filePath)
                    .chunkType(CodeChunk.ChunkType.METHOD)
                    .className(parsedClass.name())
                    .methodName(method.name())
                    .content(methodBody)
                    .startLine(method.startLine())
                    .endLine(method.endLine())
                    .build());
        }
    }
}
