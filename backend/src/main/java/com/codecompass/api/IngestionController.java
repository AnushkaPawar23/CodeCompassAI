package com.codecompass.api;

import com.codecompass.ingestion.RepoIngestionService;
import com.codecompass.parsing.ParsedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Stage 2 + 3 — REST entry-point for repository ingestion.
 *
 * <h2>Endpoint</h2>
 * <pre>POST /api/ingest</pre>
 *
 * <h2>Request</h2>
 * <pre>{@code
 * {
 *   "source": "/absolute/local/path"   // or a Git URL
 *   "type":   "local"                  // or "git"
 * }
 * }</pre>
 *
 * <h2>Response (200 OK)</h2>
 * <pre>{@code
 * {
 *   "filesParsed":   12,
 *   "classesFound":  15,
 *   "methodsFound":  47,
 *   "testFileCount": 2,
 *   "chunksCreated": 62,
 *   "durationMs":    4200,
 *   "failedFiles":   []
 * }
 * }</pre>
 *
 * <p>Processing is synchronous. Embedding large repos may take tens of seconds
 * while Ollama processes each chunk. Async support is planned for a later stage.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class IngestionController {

    private final RepoIngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody IngestRequest request) {

        // ── Validate ──────────────────────────────────────────────────────────
        if (request.source() == null || request.source().isBlank()) {
            return ResponseEntity.badRequest().body("'source' must not be blank");
        }

        String type = (request.type() != null) ? request.type().trim().toLowerCase() : "local";
        if (!"local".equals(type) && !"git".equals(type)) {
            return ResponseEntity.badRequest()
                    .body("'type' must be \"local\" or \"git\", got: \"" + request.type() + "\"");
        }

        boolean isGit = "git".equals(type);
        log.info("POST /api/ingest — type={}, source={}", type, request.source());
        long start = System.currentTimeMillis();

        // ── Execute ───────────────────────────────────────────────────────────
        try {
            RepoIngestionService.IngestionResult result =
                    ingestionService.ingest(request.source(), isGit);

            List<ParsedFile> files = result.parsedFiles();

            int classCount = files.stream()
                    .mapToInt(f -> f.classes().size())
                    .sum();

            int methodCount = files.stream()
                    .flatMap(f -> f.classes().stream())
                    .mapToInt(c -> c.methods().size())
                    .sum();

            int testCount = (int) files.stream()
                    .filter(ParsedFile::testFile)
                    .count();

            IngestResponse response = new IngestResponse(
                    files.size(),
                    classCount,
                    methodCount,
                    testCount,
                    result.chunksCreated(),
                    System.currentTimeMillis() - start,
                    result.failedFiles()
            );

            log.info("Ingestion done — files={}, classes={}, methods={}, chunks={}, failed={}, ms={}",
                    response.filesParsed(), response.classesFound(), response.methodsFound(),
                    response.chunksCreated(), response.failedFiles().size(), response.durationMs());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Bad ingestion request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());

        } catch (Exception e) {
            log.error("Ingestion failed for source='{}': {}", request.source(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Ingestion failed: " + e.getMessage());
        }
    }
}
