package com.tickefy.payment;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres base for refund integration tests (mảnh [3]). The refund leg's idempotency rests on
 * the {@code uq_payment_refund_request} UNIQUE constraint and on {@code ddl-auto=validate} matching
 * the V3 columns — neither is exercisable on the H2 unit profile, so these ITs run Flyway V1→V3 on a
 * real Postgres container. No RabbitMQ/Redis container: refund publishes no event and never touches
 * the idempotency cache; the outbox scheduler is gated off by application-test.yml.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseRefundIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("payment_test")
                .withUsername("payment_test")
                .withPassword("payment_test")
                .withReuse(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Override the H2 unit profile (application-test.yml) with the real container + Flyway ON.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
        // validate (not none) so the V3 columns are checked against the entity mapping.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // §6.12: keep the Rabbit health indicator from probing the broker during context start.
        registry.add("management.health.rabbit.enabled", () -> "false");
    }
}
