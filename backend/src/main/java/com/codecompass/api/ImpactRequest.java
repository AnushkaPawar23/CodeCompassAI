package com.codecompass.api;

/**
 * Stage 6 — request body for {@code POST /api/impact}.
 *
 * <h2>All fields required</h2>
 * <ul>
 *   <li>{@code repoId} — logical repository identifier, exactly as supplied to
 *       {@code POST /api/ingest} (e.g. a local path or Git URL).  Used to scope
 *       both the call-graph query and the code-chunk lookup so results are
 *       isolated to a single ingested repository.</li>
 *   <li>{@code targetClass} — simple name of the class that contains the method
 *       being changed (e.g. {@code ChunkingService}).</li>
 *   <li>{@code targetMethod} — simple name of the method being changed
 *       (e.g. {@code chunk}).</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * {
 *   "repoId":       "C:/path/to/backend/src",
 *   "targetClass":  "ChunkingService",
 *   "targetMethod": "chunk"
 * }
 * }</pre>
 */
public record ImpactRequest(
        String repoId,
        String targetClass,
        String targetMethod
) {}
