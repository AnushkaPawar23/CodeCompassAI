package com.codecompass.api;

/**
 * Uniform error response body returned by all {@code POST /api/*} and
 * {@code GET /api/*} endpoints on 4xx / 5xx responses.
 *
 * <h2>Shape</h2>
 * <pre>{@code
 * {
 *   "status":  400,
 *   "error":   "Bad Request",
 *   "message": "'targetClass' must not be blank"
 * }
 * }</pre>
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code status}  — HTTP status code (mirrors the HTTP response status).</li>
 *   <li>{@code error}   — Short human-readable status phrase.</li>
 *   <li>{@code message} — Developer-facing description of what went wrong.</li>
 * </ul>
 *
 * <p>All five API endpoints ({@code /api/ingest}, {@code /api/qa},
 * {@code /api/graph/dependents}, {@code /api/impact}, {@code /api/endpoints})
 * return this shape on error, so the React frontend can handle errors
 * uniformly via a single {@code response.json().message} access pattern.
 */
public record ErrorResponse(
        int    status,
        String error,
        String message
) {
    /** Factory — 400 Bad Request. */
    public static ErrorResponse badRequest(String message) {
        return new ErrorResponse(400, "Bad Request", message);
    }

    /** Factory — 500 Internal Server Error. */
    public static ErrorResponse serverError(String message) {
        return new ErrorResponse(500, "Internal Server Error", message);
    }
}
