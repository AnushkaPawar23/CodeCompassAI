package com.codecompass.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration for the CodeCompass AI backend.
 *
 * <h2>Purpose</h2>
 * <p>The React frontend (running on {@code http://localhost:3000} during
 * {@code npm start} or {@code http://localhost:5173} during Vite dev) calls
 * this backend at {@code http://localhost:8080} from a browser.  Without
 * explicit CORS headers the browser's same-origin policy will block every
 * cross-origin request with a network error before it even reaches the
 * Java handler.
 *
 * <h2>Allowed origins</h2>
 * <ul>
 *   <li>{@code http://localhost:3000} — Create-React-App dev server</li>
 *   <li>{@code http://localhost:5173} — Vite dev server</li>
 *   <li>{@code http://localhost:4173} — Vite preview</li>
 * </ul>
 * <p>Production origins should be added here (or overridden via
 * {@code codecompass.cors.allowed-origins}) before any public deployment.
 *
 * <h2>Allowed methods</h2>
 * <p>All HTTP verbs used by the API: {@code GET}, {@code POST}, {@code OPTIONS}.
 * {@code OPTIONS} is required for browser pre-flight requests.
 *
 * <h2>Security note</h2>
 * <p>This configuration is intentionally permissive for local development.
 * For a production deployment, restrict {@code allowedOrigins} to the exact
 * frontend hostname and set {@code allowCredentials} only if session cookies
 * are in use.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // ── Local dev frontends ────────────────────────────────────────
                .allowedOrigins(
                        "http://localhost:3000",   // Create-React-App
                        "http://localhost:5173",   // Vite dev
                        "http://localhost:4173"    // Vite preview
                )
                // ── Allowed HTTP methods ───────────────────────────────────────
                .allowedMethods("GET", "POST", "OPTIONS")
                // ── Allowed request headers ────────────────────────────────────
                .allowedHeaders("*")
                // ── Expose response headers to JS ──────────────────────────────
                .exposedHeaders("Content-Type")
                // ── Cache pre-flight results for 1 hour ───────────────────────
                .maxAge(3600);
    }
}
