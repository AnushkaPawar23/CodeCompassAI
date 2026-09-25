package com.codecompass.api;

import com.codecompass.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 4 — REST entry-point for Q&amp;A RAG queries.
 *
 * <h2>Endpoint</h2>
 * <pre>POST /api/qa</pre>
 *
 * <h2>Request</h2>
 * <pre>{@code
 * {
 *   "question": "where is the ingestion pipeline implemented?",
 *   "repoId":   "optional — not used for retrieval scoping in Stage 4",
 *   "topK":     5   // optional; defaults to codecompass.rag.default-top-k
 * }
 * }</pre>
 *
 * <h2>Response (200 OK)</h2>
 * <pre>{@code
 * {
 *   "answer": "The ingestion pipeline is implemented in …",
 *   "sources": [
 *     {
 *       "filePath":   ".../ingestion/RepoIngestionService.java",
 *       "className":  "RepoIngestionService",
 *       "methodName": "ingest",
 *       "chunkType":  "METHOD",
 *       "startLine":  45,
 *       "endLine":    92
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Processing is synchronous: Ollama embedding (~100 ms) + pgvector search (~10 ms)
 * + Groq LLM call (~1–3 s) happen in sequence.  Total latency is typically 2–5 s
 * for a well-warmed Ollama instance.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class QaController {

    private final RagService ragService;

    @PostMapping("/qa")
    public ResponseEntity<?> ask(@RequestBody QaRequest request) {

        // ── Validate ──────────────────────────────────────────────────────────
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse.badRequest("'question' must not be blank"));
        }

        log.info("POST /api/qa — question='{}'", abbreviate(request.question(), 80));

        // ── Execute ───────────────────────────────────────────────────────────
        try {
            QaResponse response = ragService.answer(request.question(), request.topK());
            log.info("QA answered — {} source(s) cited", response.sources().size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("QA failed for question='{}': {}", request.question(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.serverError("QA failed: " + e.getMessage()));
        }
    }

    /** Returns the first {@code maxLen} chars of {@code s}, with "…" if truncated. */
    private static String abbreviate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
