package com.codecompass;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test – verifies the Spring application context loads cleanly.
 *
 * NOTE: This test requires the Postgres container to be running.
 * Run: docker-compose up -d  (from the project root) before executing tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class CodeCompassApplicationTests {

    @Test
    void contextLoads() {
        // If the application context starts without throwing, the test passes.
    }
}
