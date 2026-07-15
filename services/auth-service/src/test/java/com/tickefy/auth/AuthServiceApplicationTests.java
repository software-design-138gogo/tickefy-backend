package com.tickefy.auth;

import org.junit.jupiter.api.Test;

/**
 * Context load smoke test — verifies full application context starts cleanly with
 * Testcontainers Postgres + Redis (via BaseIntegrationTest singleton containers).
 */
class AuthServiceApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
        // If Spring context loads without error, the test passes.
        // This validates: Flyway V1+V2 apply cleanly, Redis connects, JWT keys load.
    }
}
