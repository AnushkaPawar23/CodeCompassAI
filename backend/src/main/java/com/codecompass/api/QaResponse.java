package com.codecompass.api;

import java.util.List;

/**
 * Stage 4 — Response body for {@code POST /api/qa}.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code answer} — the LLM-generated answer (Groq llama-3.3-70b-versatile).</li>
 *   <li>{@code sources} — the ranked list of code chunks used to construct the
 *       prompt, ordered by cosine similarity (most relevant first).  Each entry
 *       is traceable to an exact file, class/method, and line range.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * {
 *   "answer": "The ingestion pipeline is implemented in RepoIngestionService...",
 *   "sources": [
 *     {
 *       "filePath": ".../ingestion/RepoIngestionService.java",
 *       "className": "RepoIngestionService",
 *       "methodName": "ingest",
 *       "chunkType": "METHOD",
 *       "startLine": 45,
 *       "endLine": 92
 *     }
 *   ]
 * }
 * }</pre>
 */
public record QaResponse(String answer, List<SourceReference> sources) {

    /**
     * A single code chunk that contributed to the LLM answer.
     *
     * @param filePath   absolute path of the source file
     * @param className  simple name of the enclosing type
     * @param methodName simple name of the method ({@code null} for CLASS chunks)
     * @param chunkType  {@code "CLASS"} or {@code "METHOD"}
     * @param startLine  1-based start line in the source file
     * @param endLine    1-based end line in the source file
     */
    public record SourceReference(
            String filePath,
            String className,
            String methodName,
            String chunkType,
            int startLine,
            int endLine
    ) {}
}
