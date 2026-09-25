package com.codecompass.api;

import com.codecompass.parsing.EndpointDescriptor;
import com.codecompass.parsing.SpringMvcEndpointScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Stage 7 — REST controller exposing the endpoint-discovery API.
 *
 * <h2>Endpoint</h2>
 * <pre>GET /api/endpoints?repoId=...</pre>
 *
 * <p>Uses {@code repoId} as a query parameter (not a path variable) because
 * the repoId for local repositories is a filesystem path containing slashes
 * (e.g. {@code C:/path/to/backend/src}), which cannot be URL-path-encoded
 * reliably as a {@code @PathVariable}.
 *
 * <h2>Request parameters</h2>
 * <ul>
 *   <li>{@code repoId} (required) — the logical repository identifier, exactly
 *       as supplied to {@code POST /api/ingest}.  For local repos this is an
 *       absolute filesystem path; for Git repos the clone was deleted after
 *       ingestion and the scan cannot be performed — a 400 is returned.</li>
 * </ul>
 *
 * <h2>Response (200 OK)</h2>
 * <pre>{@code
 * {
 *   "repoId":        "C:/path/to/backend/src",
 *   "endpointCount": 4,
 *   "endpoints": [
 *     {
 *       "httpMethod":      "POST",
 *       "path":            "/api/ingest",
 *       "controllerClass": "IngestionController",
 *       "handlerMethod":   "ingest",
 *       "filePath":        "C:/path/to/IngestionController.java",
 *       "startLine":       53
 *     },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <h2>Error responses</h2>
 * <ul>
 *   <li>{@code 400 Bad Request} — {@code repoId} is blank, or the path does
 *       not exist as a directory on disk (e.g. a Git repo whose clone was
 *       cleaned up after ingestion).</li>
 *   <li>{@code 500 Internal Server Error} — unexpected I/O error during the
 *       file walk or parse.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class EndpointController {

    private final SpringMvcEndpointScanner scanner;

    /**
     * Response DTO for the endpoint-discovery query.
     *
     * @param repoId        the repository identifier that was scanned
     * @param endpointCount number of Spring MVC endpoints discovered
     * @param endpoints     ordered list of endpoint descriptors
     */
    public record EndpointsResponse(
            String repoId,
            int endpointCount,
            List<EndpointDescriptor> endpoints
    ) {}

    /**
     * Scans the specified repository for Spring MVC REST endpoints.
     *
     * @param repoId absolute filesystem path of the ingested repository
     * @return {@link EndpointsResponse} with all discovered endpoints
     */
    @GetMapping("/endpoints")
    public ResponseEntity<?> getEndpoints(@RequestParam("repoId") String repoId) {

        // ── Validate ──────────────────────────────────────────────────────────
        if (repoId == null || repoId.isBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse.badRequest("'repoId' must not be blank"));
        }

        Path repoPath = Paths.get(repoId).toAbsolutePath().normalize();

        if (!Files.isDirectory(repoPath)) {
            return ResponseEntity.badRequest().body(ErrorResponse.badRequest(
                    "Repository path does not exist or is not a directory: '" + repoId + "'. " +
                    "Note: Git repositories are cloned to a temporary directory that is deleted " +
                    "after ingestion — only local (type=local) repositories can be scanned."));
        }

        log.info("GET /api/endpoints — repoId='{}'", repoId);

        // ── Scan ──────────────────────────────────────────────────────────────
        try {
            List<EndpointDescriptor> endpoints = scanner.scan(repoPath);

            EndpointsResponse response = new EndpointsResponse(
                    repoId,
                    endpoints.size(),
                    endpoints
            );

            log.info("Endpoint discovery done — {} endpoint(s) found for repoId='{}'",
                    endpoints.size(), repoId);

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("Endpoint scan I/O error for repoId='{}': {}", repoId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.serverError("Endpoint scan failed (I/O error): " + e.getMessage()));
        } catch (Exception e) {
            log.error("Endpoint scan failed for repoId='{}': {}", repoId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.serverError("Endpoint scan failed: " + e.getMessage()));
        }
    }
}
