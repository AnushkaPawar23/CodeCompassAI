package com.codecompass.api;

/**
 * Request body for {@code POST /api/ingest}.
 *
 * @param source a local filesystem path (absolute or relative to the JVM working
 *               directory) OR a public Git remote URL
 * @param type   {@code "local"} to use the path directly;
 *               {@code "git"} to clone via JGit first
 */
public record IngestRequest(String source, String type) {}
