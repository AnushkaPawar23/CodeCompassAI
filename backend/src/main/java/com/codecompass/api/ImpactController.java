package com.codecompass.api;

import com.codecompass.graph.ImpactAnalysisService;
import com.codecompass.graph.ImpactAnalysisService.ImpactResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 6 — REST controller exposing the change-impact analysis API.
 * Stage 8a — response now includes risk levels per dependent and risk-count summaries.
 * Stage 8b — response now includes test-coverage indicators per dependent.
 *
 * <h2>Endpoint</h2>
 * <pre>POST /api/impact</pre>
 *
 * <h2>Request body</h2>
 * <pre>{@code
 * {
 *   "repoId":       "C:/path/to/backend/src",
 *   "targetClass":  "ChunkingService",
 *   "targetMethod": "chunk"
 * }
 * }</pre>
 *
 * <h2>Response (200 OK)</h2>
 * <pre>{@code
 * {
 *   "targetClass":            "ChunkingService",
 *   "targetMethod":           "chunk",
 *   "repoId":                 "C:/path/to/backend/src",
 *   "dependentsFound":        3,
 *   "highRiskCount":          1,
 *   "mediumRiskCount":        1,
 *   "lowRiskCount":           1,
 *   "untestedDependentsCount": 2,
 *   "dependents": [
 *     {
 *       "callerClass":     "RepoIngestionService",
 *       "callerMethod":    "parseAndEmbed",
 *       "callerFile":      "...RepoIngestionService.java",
 *       "callerStartLine": 129,
 *       "callerEndLine":   155,
 *       "depth":           1,
 *       "riskLevel":       "HIGH",
 *       "hasTestCoverage": false
 *     },
 *     ...
 *   ],
 *   "explanation": "HIGH RISK: RepoIngestionService#parseAndEmbed directly depends on..."
 * }
 * }</pre>
 *
 * <h2>Risk levels (Stage 8a)</h2>
 * <ul>
 *   <li>{@code HIGH}   — depth 1: the dependent directly calls the changed method.</li>
 *   <li>{@code MEDIUM} — depth 2: one hop removed.</li>
 *   <li>{@code LOW}    — depth 3+: further transitive dependency.</li>
 * </ul>
 *
 * <h2>Test coverage (Stage 8b)</h2>
 * <p>{@code hasTestCoverage} is {@code true} when at least one file under
 * {@code src/test/java} contains both the dependent's class name and method
 * name as substrings.  {@code untestedDependentsCount} is the number of
 * dependents for which no such evidence was found.
 *
 * <h2>Zero-dependents case</h2>
 * <p>When no callers are found the endpoint returns 200 OK with
 * {@code dependentsFound: 0}, all counts zero, an empty {@code dependents}
 * list, and an {@code explanation} message indicating the method is safe to
 * change in isolation.  No LLM call is made in this case.
 *
 * <h2>Error responses</h2>
 * <ul>
 *   <li>{@code 400 Bad Request} — if {@code repoId}, {@code targetClass},
 *       or {@code targetMethod} is missing or blank.</li>
 *   <li>{@code 500 Internal Server Error} — unexpected failure in graph
 *       traversal or LLM call.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ImpactController {

    private final ImpactAnalysisService impactAnalysisService;

    /**
     * Analyses the change impact for the specified method.
     *
     * @param request the impact analysis request
     * @return {@link ImpactResult} containing the dependent list and
     *         LLM-generated explanation
     */
    @PostMapping("/impact")
    public ResponseEntity<?> analyseImpact(@RequestBody ImpactRequest request) {

        // ── Validate ──────────────────────────────────────────────────────────
        if (request.repoId() == null || request.repoId().isBlank()) {
            return ResponseEntity.badRequest().body("'repoId' must not be blank");
        }
        if (request.targetClass() == null || request.targetClass().isBlank()) {
            return ResponseEntity.badRequest().body("'targetClass' must not be blank");
        }
        if (request.targetMethod() == null || request.targetMethod().isBlank()) {
            return ResponseEntity.badRequest().body("'targetMethod' must not be blank");
        }

        log.info("POST /api/impact — repoId='{}', target={}.{}",
                request.repoId(), request.targetClass(), request.targetMethod());

        // ── Execute ───────────────────────────────────────────────────────────
        try {
            ImpactResult result = impactAnalysisService.analyse(
                    request.repoId(),
                    request.targetClass(),
                    request.targetMethod());

            log.info("Impact analysis done — {}.{} has {} dependent(s)",
                    request.targetClass(), request.targetMethod(), result.dependentsFound());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Impact analysis failed for {}.{}: {}",
                    request.targetClass(), request.targetMethod(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Impact analysis failed: " + e.getMessage());
        }
    }
}
