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
 * T-csv-cron-scan gate-off: verifies CsvCronScanner bean is ABSENT when
 * app.csv.scan.enabled=false (i.e., @ConditionalOnProperty guard works).
 *
 * <p>Separate @SpringBootTest class required — Spring caches one context per unique
 * configuration set; mixing scan.enabled=true and scan.enabled=false in the same class
 * would share a context incorrectly (mirrors CsvStuckReaperGateOffTest pattern).
 *
 * <p>AC6-gate-off: CsvCronScanner bean absent when app.csv.scan.enabled=false →
 * ApplicationContext.getBean(CsvCronScanner.class) throws NoSuchBeanDefinitionException.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = {
    "app.csv.scan.enabled=false",             // cron-scan DISABLED — bean must be absent
    "app.csv.reaper.enabled=false",           // reaper off too
    "app.messaging.outbox.enabled=false",     // drainer off — no broker
    "app.csv.worker.auto-trigger=false"       // no auto-trigger either
})
class CsvCronScannerGateOffTest {

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres only (Flyway needs real DB for context boot)
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_cron_gate_off_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    // -----------------------------------------------------------------------
    // @DynamicPropertySource — mirrors CsvStuckReaperGateOffTest
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
        registry.add("app.object-storage.endpoint", () -> "http://localhost:19910");
        registry.add("app.object-storage.access-key", () -> "minioadmin");
        registry.add("app.object-storage.secret-key", () -> "minioadmin");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");

        // External services — not called
        registry.add("app.inventory.base-url", () -> "http://localhost:19911");
        registry.add("app.event.base-url", () -> "http://localhost:19912");

        // JWT
        registry.add("app.jwt.public-key", () -> "classpath:keys/jwt-dev-public.pem");
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");

        // Batch defaults
        registry.add("app.csv.batch-size", () -> "500");
        registry.add("app.csv.error-threshold", () -> "0.5");

        // RabbitMQ — not used
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "15697");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    ApplicationContext ctx;

    // -----------------------------------------------------------------------
    // AC6-gate-off: CsvCronScanner bean ABSENT when scan.enabled=false
    // -----------------------------------------------------------------------

    @Test
    void ac6_gateOff_scanEnabledFalse_beanAbsent() {
        // getBeanNamesForType returns empty array when bean is absent
        String[] names = ctx.getBeanNamesForType(CsvCronScanner.class);
        assertThat(names)
                .as("AC6: CsvCronScanner must NOT be in context when app.csv.scan.enabled=false")
                .isEmpty();

        // Confirm via getBean() throws NoSuchBeanDefinitionException
        assertThrows(
                NoSuchBeanDefinitionException.class,
                () -> ctx.getBean(CsvCronScanner.class),
                "AC6: getBean(CsvCronScanner.class) must throw NoSuchBeanDefinitionException when disabled");
    }
}
