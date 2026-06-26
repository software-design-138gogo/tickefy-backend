package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * REGRESSION (CLOSEOUT-csv-full-e2e §B5) — promoteStaging native query on a NON-public schema.
 *
 * <p>The runtime bug: {@code VipGuestRepository.promoteStaging} is a native query with UNQUALIFIED
 * table names ({@code INSERT INTO vip_guests … FROM vip_guest_staging}). At runtime the tables live
 * in schema {@code csv_ingestion_service}, but the JDBC connection had no {@code currentSchema}, so
 * {@code search_path = "$user", public} → {@code ERROR: relation "vip_guests" does not exist}
 * (SQLState 42P01) → every import FAILED at promote. JPA ops survived (hibernate default_schema
 * qualifies them); native queries do NOT honor default_schema — they resolve via search_path.
 *
 * <p>The sibling repository/finalize ITs DO exercise promote but force schema={@code public}
 * (search_path already includes public) so the unqualified names resolve and the bug is masked —
 * §8 lesson "Testcontainers/H2 PASS ≠ runtime compose".
 *
 * <p>This test runs promote on schema {@code csv_ingestion_service} with the JDBC url carrying
 * {@code ?currentSchema=…} (mirrors the production fix in application.yml). It MUST FAIL if the
 * {@code currentSchema} param is removed from {@link #schemaProperties} (reproducing the runtime
 * bug) and PASS with it (the fix). The negative test pins the mechanism directly: a connection with
 * {@code search_path = public} cannot resolve the unqualified table.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class VipGuestPromoteSchemaIntegrationTest {

    /** NON-public schema — same name as runtime compose (§3 database-per-service). */
    static final String SCHEMA = "csv_ingestion_service";

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                        .withDatabaseName("csv_promote_schema_test")
                        .withUsername("csv_test")
                        .withPassword("csv_test")
                        .withReuse(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void schemaProperties(DynamicPropertyRegistry registry) {
        // ⭐ REPRODUCE RUNTIME: non-public schema + currentSchema in the JDBC url (mirrors prod fix).
        // Drop the "currentSchema=" param below → search_path=public → promoteStaging throws
        // InvalidDataAccessResourceUsageException "relation \"vip_guests\" does not exist" (42P01).
        // Testcontainers' getJdbcUrl() already carries "?loggerLevel=OFF", so append with the right sep.
        registry.add(
                "spring.datasource.url",
                () -> {
                    String base = POSTGRES.getJdbcUrl();
                    return base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
                });
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private VipGuestRepository vipGuestRepository;

    @Autowired
    private VipGuestStagingRepository stagingRepository;

    @Autowired
    private ImportJobRepository importJobRepository;

    @Autowired
    private DataSource dataSource;

    // -----------------------------------------------------------------------
    // POSITIVE: promote resolves the unqualified table on a non-public schema
    // because currentSchema put it on the connection search_path (the fix).
    // -----------------------------------------------------------------------

    @Test
    void promote_onNonPublicSchema_withCurrentSchema_resolvesAndInserts() throws SQLException {
        // Guard: the connection search_path really points at the non-public schema (NOT public),
        // i.e. this test exercises the same condition as runtime compose.
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                var rs = st.executeQuery("SHOW search_path")) {
            rs.next();
            assertThat(rs.getString(1))
                    .as("currentSchema put %s on search_path (not public)", SCHEMA)
                    .contains(SCHEMA);
        }

        UUID concertId = UUID.randomUUID();
        UUID jobId = seedJob(concertId);
        seedStaging(jobId, concertId, "g1@example.com", "Guest One", 1);
        seedStaging(jobId, concertId, "g2@example.com", "Guest Two", 2);

        // Native unqualified INSERT INTO vip_guests … FROM vip_guest_staging — the runtime failure point.
        int inserted = vipGuestRepository.promoteStaging(jobId);

        assertThat(inserted).as("promote inserted both staged rows").isEqualTo(2);
        assertThat(vipGuestRepository.findByConcertId(concertId))
                .as("vip_guests promoted on non-public schema")
                .hasSize(2);
    }

    // -----------------------------------------------------------------------
    // NEGATIVE: pins the mechanism — a connection on search_path=public (= runtime
    // BEFORE the fix) cannot resolve the unqualified table → SQLState 42P01.
    // -----------------------------------------------------------------------

    @Test
    void promote_searchPathPublic_cannotResolveTable_reproducesBug() throws SQLException {
        UUID concertId = UUID.randomUUID();
        UUID jobId = seedJob(concertId);
        seedStaging(jobId, concertId, "n1@example.com", "N One", 1);

        // Same unqualified statement as VipGuestRepository.promoteStaging, run under search_path=public
        // to simulate a datasource WITHOUT currentSchema (the bug). The table lives in
        // csv_ingestion_service, so it is invisible → relation does not exist.
        String promoteSql =
                "INSERT INTO vip_guests "
                        + "(id, concert_id, email, full_name, ticket_type_id, ticket_type_name, import_job_id) "
                        + "SELECT gen_random_uuid(), s.concert_id, s.email, s.full_name, "
                        + "s.ticket_type_id, s.ticket_type_name, s.import_job_id "
                        + "FROM vip_guest_staging s WHERE s.import_job_id = '" + jobId + "' "
                        + "ON CONFLICT (concert_id, email) DO NOTHING";

        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.execute("SET search_path TO public");
            assertThatThrownBy(() -> st.executeUpdate(promoteSql))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e ->
                            assertThat(((SQLException) e).getSQLState())
                                    .as("42P01 = undefined_table (relation does not exist)")
                                    .isEqualTo("42P01"));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — seed parent job (FK) then staging rows, flushed so the native
    // promote (raw JDBC within the same TX) sees them.
    // -----------------------------------------------------------------------

    private UUID seedJob(UUID concertId) {
        ImportJobEntity job =
                ImportJobEntity.builder()
                        .id(UUID.randomUUID())
                        .concertId(concertId)
                        .organizerId(UUID.randomUUID())
                        .source("UPLOAD")
                        .objectKey("csv-imports/" + UUID.randomUUID() + ".csv")
                        .status("PROCESSING")
                        .totalRows(0)
                        .successRows(0)
                        .failedRows(0)
                        .duplicateRows(0)
                        .attemptCount(0)
                        .build();
        return importJobRepository.saveAndFlush(job).getId();
    }

    private void seedStaging(UUID jobId, UUID concertId, String email, String fullName, int line) {
        VipGuestStagingEntity row =
                VipGuestStagingEntity.builder()
                        .importJobId(jobId)
                        .concertId(concertId)
                        .email(email)
                        .fullName(fullName)
                        .ticketTypeId(UUID.randomUUID())
                        .ticketTypeName("Gold")
                        .lineNumber(line)
                        .build();
        stagingRepository.saveAndFlush(row);
    }
}
