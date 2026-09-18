package com.codecompass.api;

/**
 * Stage 4 — Request body for {@code POST /api/qa}.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code repoId} — optional logical repository identifier.  Accepted for
 *       forward-compatibility with multi-repo support (Stage 5+); not currently
 *       used to scope the similarity search (Stage 4 searches all chunks).</li>
 *   <li>{@code question} — the plain-English question; mandatory.</li>
 *   <li>{@code topK} — optional number of chunks to retrieve.  Defaults to
 *       {@code codecompass.rag.default-top-k} (5) when {@code null}.
 *       Clamped to {@code codecompass.rag.max-results} (10) as a ceiling.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * {
 *   "question": "where is the ingestion pipeline implemented?"
 * }
 * }</pre>
 */
public record QaRequest(String repoId, String question, Integer topK) {}
