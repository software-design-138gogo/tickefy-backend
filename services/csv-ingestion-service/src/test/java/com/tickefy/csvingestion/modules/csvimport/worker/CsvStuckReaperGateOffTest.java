package com.tickefy.csvingestion.modules.csvimport.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-6b / VIỆC 3 — Gate-off: verifies StuckImportReaper bean is ABSENT when
 * app.csv.reaper.enabled=false (i.e., the @ConditionalOnProperty guard works).
 *
 * <p>Separate @SpringBootTest class needed because Spring caches one context per
 * unique configuration set; mixing enabled=true and enabled=false in one class
 * would share context incorrectly.
 *
 * <p>AC: StuckImportReaper bean absent → ApplicationContext.getBeanNamesForType returns empty /
 * ctx.getBean(StuckImportReaper.class) throws NoSuchBeanDefinitionException.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = {
    "app.csv.reaper.enabled=false",      // reaper DISABLED — bean must be absent
    "app.messaging.outbox.enabled=false" // drainer also off — no broker needed
})
class CsvStuckReaperGateOffTest {

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres only (Flyway needs real DB for context boot)
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_reaper_gate_off_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    // -----------------------------------------------------------------------
    // @DynamicPropertySource — mirrors reaper IT
    // -----------------------------------------------------------------------

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.database.schema", () -> "public");

        // MinIO — dummy; bean created but never used
        registry.add("app.object-storage.endpoint", () -> "http://localhost:19900");
        registry.add("app.object-storage.access-key", () -> "minioadmin");
        registry.add("app.object-storage.secret-key", () -> "minioadmin");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");

        // External services — not called
        registry.add("app.inventory.base-url", () -> "http://localhost:19901");
        registry.add("app.event.base-url", () -> "http://localhost:19902");

        // JWT
        registry.add("app.jwt.public-key", () -> "classpath:keys/jwt-dev-public.pem");
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");

        // Worker auto-trigger OFF
        registry.add("app.csv.worker.auto-trigger", () -> "false");
        registry.add("app.csv.batch-size", () -> "500");
        registry.add("app.csv.error-threshold", () -> "0.5");

        // RabbitMQ — not used
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "15699");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    ApplicationContext ctx;

    // -----------------------------------------------------------------------
    // AC7-gate-off: StuckImportReaper bean ABSENT when enabled=false
    // -----------------------------------------------------------------------

    @Test
    void ac7_gateOff_reaperEnabledFalse_beanAbsent() {
        // getBeanNamesForType returns empty array when bean is absent
        String[] names = ctx.getBeanNamesForType(StuckImportReaper.class);
        assertThat(names)
                .as("AC7: StuckImportReaper must NOT be in context when app.csv.reaper.enabled=false")
                .isEmpty();

        // Alternatively confirm via getBean() throws
        assertThrows(
                NoSuchBeanDefinitionException.class,
                () -> ctx.getBean(StuckImportReaper.class),
                "AC7: getBean(StuckImportReaper.class) must throw NoSuchBeanDefinitionException when disabled");
    }
}
