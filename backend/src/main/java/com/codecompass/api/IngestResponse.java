package com.codecompass.api;

import java.util.List;

/**
 * Response body returned by {@code POST /api/ingest} on success.
 *
 * @param repoId            logical identifier for this repo (normalised source path or Git URL)
 * @param filesParsed       number of .java files successfully parsed
 * @param classesFound      total number of type declarations across all parsed files
 * @param methodsFound      total number of method declarations across all types
 * @param testFileCount     number of parsed files that live under a test source tree
 * @param chunksCreated     number of code chunks successfully embedded and stored in pgvector
 * @param graphEdgesCreated number of intra-project call-graph edges persisted (Stage 5);
 *                          0 if graph build was skipped or no intra-project calls were found
 * @param durationMs        wall-clock time of the full ingestion + parse + embed + graph run (ms)
 * @param failedFiles       relative paths of files that could not be parsed (empty list = clean run)
 */
public record IngestResponse(
        String repoId,
        int filesParsed,
        int classesFound,
        int methodsFound,
        int testFileCount,
        int chunksCreated,
        int graphEdgesCreated,
        long durationMs,
        List<String> failedFiles
) {}
