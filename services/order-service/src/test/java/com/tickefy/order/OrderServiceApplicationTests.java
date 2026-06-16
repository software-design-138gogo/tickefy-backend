package com.tickefy.order;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: context loads with Testcontainers Postgres.
 * Extends BaseIntegrationTest to reuse the singleton PG container.
 */
class OrderServiceApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
        // Just verify context starts correctly
    }
}
