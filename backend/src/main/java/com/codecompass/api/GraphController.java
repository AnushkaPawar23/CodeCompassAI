package com.codecompass.api;

import com.codecompass.graph.GraphTraversalService;
import com.codecompass.graph.GraphTraversalService.DependentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Stage 5 — REST controller exposing the call-graph traversal API.
 *
 * <h2>Endpoint</h2>
 * <pre>GET /api/graph/dependents?repoId=...&amp;class=...&amp;method=...</pre>
 *
 * <h2>Request parameters</h2>
 * <ul>
 *   <li>{@code class} (required) — simple class name of the target method's class,
 *       e.g. {@code RepoIngestionService}</li>
 *   <li>{@code method} (required) — simple method name, e.g. {@code ingest}</li>
 *   <li>{@code repoId} (optional) — logical repository identifier as used in
 *       {@code POST /api/ingest}. Defaults to the last-ingested repo if omitted
 *       (convenience for single-repo demo; not implemented here — must be supplied).</li>
 * </ul>
 *
 * <h2>Response (200 OK)</h2>
 * <pre>{@code
 * {
 *   "targetClass":   "RepoIngestionService",
 *   "targetMethod":  "ingest",
 *   "repoId":        "c:/path/to/backend/src",
 *   "totalCount":    1,
 *   "dependents": [
 *     {
 *       "callerClass":     "IngestionController",
 *       "callerMethod":    "ingest",
 *       "callerFile":      "c:/path/to/IngestionController.java",
 *       "callerStartLine": 53,
 *       "callerEndLine":   116,
 *       "depth":           1
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Error responses</h2>
 * <ul>
 *   <li>{@code 400 Bad Request} — if {@code class} or {@code method} or {@code repoId}
 *       is missing / blank.</li>
 *   <li>{@code 500 Internal Server Error} — unexpected traversal failure.</li>
 * </ul>
 *
 * <h2>No LLM involvement</h2>
 * <p>This endpoint returns raw graph traversal data only. LLM-powered explanations
 * ("why does this change impact these callers?") are added in Stage 6.
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Slf4j
public class GraphController {

    private final GraphTraversalService traversalService;

    /**
     * Response DTO for the dependents query.
     *
     * @param targetClass  simple class name of the queried target method
     * @param targetMethod simple method name of the queried target
     * @param repoId       repository scope used for the query
     * @param totalCount   number of transitive dependents found
     * @param dependents   ordered list of dependent methods (BFS order, depth ascending)
     */
    public record DependentsResponse(
            String targetClass,
            String targetMethod,
            String repoId,
            int totalCount,
            List<DependentMethod> dependents
    ) {}

    /**
     * Returns all methods that transitively depend on (call) the specified method.
     *
     * @param className  simple class name of the target (required)
     * @param methodName simple method name of the target (required)
     * @param repoId     repository scope (required)
     * @return {@link DependentsResponse} with the transitive caller list
     */
    @GetMapping("/dependents")
    public ResponseEntity<?> getDependents(
            @RequestParam("class")    String className,
            @RequestParam("method")   String methodName,
            @RequestParam("repoId")   String repoId) {

        // ── Validate ──────────────────────────────────────────────────────────
        if (className == null || className.isBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse.badRequest("'class' parameter must not be blank"));
        }
        if (methodName == null || methodName.isBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse.badRequest("'method' parameter must not be blank"));
        }
        if (repoId == null || repoId.isBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse.badRequest("'repoId' parameter must not be blank"));
        }

        log.info("GET /api/graph/dependents — repoId='{}', class={}, method={}",
                repoId, className, methodName);

        // ── Traverse ──────────────────────────────────────────────────────────
        try {
            List<DependentMethod> dependents =
                    traversalService.findTransitiveDependents(repoId, className, methodName);

            DependentsResponse response = new DependentsResponse(
                    className,
                    methodName,
                    repoId,
                    dependents.size(),
                    dependents
            );

            log.info("Dependents result — {}.{} has {} transitive dependent(s)",
                    className, methodName, dependents.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Graph traversal failed for {}.{}: {}", className, methodName, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.serverError("Graph traversal failed: " + e.getMessage()));
        }
    }
}
