package com.codecompass.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Global CORS configuration for the CodeCompass AI backend.
 *
 * <h2>Purpose</h2>
 * <p>The React frontend calls this backend from a browser. Without explicit CORS
 * headers, the browser's same-origin policy blocks cross-origin requests.
 *
 * <h2>Allowed origins</h2>
 * Configured via {@code codecompass.cors.allowed-origins} (or environment variable
 * {@code CORS_ALLOWED_ORIGINS}). Defaults to localhost dev servers and all Vercel
 * preview/production deployments ({@code https://*.vercel.app}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${codecompass.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:4173,https://*.vercel.app}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addMapping("/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type")
                .maxAge(3600);
    }
}
