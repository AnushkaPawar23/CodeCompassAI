package com.codecompass.parsing;

/**
 * Stage 7 — immutable descriptor for a single Spring MVC REST endpoint
 * discovered by {@link SpringMvcEndpointScanner}.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code httpMethod} — HTTP verb in upper-case (e.g. {@code "GET"},
 *       {@code "POST"}).  For {@code @RequestMapping} methods without an
 *       explicit {@code method} attribute the value is {@code "ANY"} to
 *       indicate that the endpoint matches all HTTP methods.</li>
 *   <li>{@code path} — fully-qualified URL path, combining the
 *       class-level {@code @RequestMapping} prefix (if present) with the
 *       method-level mapping annotation value
 *       (e.g. {@code "/api/ingest"}).</li>
 *   <li>{@code controllerClass} — simple name of the controller class that
 *       declares this endpoint (e.g. {@code "IngestionController"}).</li>
 *   <li>{@code handlerMethod} — simple name of the Java method that handles
 *       this endpoint (e.g. {@code "ingest"}).</li>
 *   <li>{@code filePath} — absolute path to the {@code .java} source file
 *       that contains the handler method.</li>
 *   <li>{@code startLine} — 1-based line number of the handler method
 *       declaration (for IDE navigation).</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * {
 *   "httpMethod":      "POST",
 *   "path":            "/api/ingest",
 *   "controllerClass": "IngestionController",
 *   "handlerMethod":   "ingest",
 *   "filePath":        "C:/path/to/IngestionController.java",
 *   "startLine":       53
 * }
 * }</pre>
 */
public record EndpointDescriptor(
        String httpMethod,
        String path,
        String controllerClass,
        String handlerMethod,
        String filePath,
        int    startLine
) {}
