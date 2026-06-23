package com.tickefy.csvingestion.modules.csvimport;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for csv-ingestion-service repository integration tests.
 * Starts Postgres ONCE (static container, reused across test classes).
 * Uses @DataJpaTest — lightweight: only JPA slice, no web/security.
 * @DynamicPropertySource overrides application-test.yml H2 config with real Postgres.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public abstract class BaseRepositoryIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                        .withDatabaseName("csv_ingestion_test")
                        .withUsername("csv_test")
                        .withPassword("csv_test")
                        .withReuse(true);

        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Use "public" schema in test container for simplicity (matches sibling services)
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.enabled", () -> "true");
        // ddl-auto=validate requires Flyway to create schema first
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
