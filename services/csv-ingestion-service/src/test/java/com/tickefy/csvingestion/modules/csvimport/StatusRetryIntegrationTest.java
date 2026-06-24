package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
import io.jsonwebtoken.Jwts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-3b-2: Status + Retry endpoint integration tests.
 *
 * <p>AC map:
 * <ul>
 *   <li>AC-STATUS-1: GET status PENDING job owned by caller → 200, status=PENDING, summary.totalRows=0, errorRows=[].</li>
 *   <li>AC-STATUS-2: GET status missing UUID → 404 IMPORT_JOB_NOT_FOUND.</li>
 *   <li>AC-STATUS-3a: GET status wrong owner (organizer) → 403 FORBIDDEN.</li>
 *   <li>AC-STATUS-3b: GET status wrong owner but ADMIN token → 200 (admin bypass).</li>
 *   <li>AC-RETRY-1: POST retry FAILED job → 200 status=PENDING; staging rows deleted; job re-fetched is PENDING.</li>
 *   <li>AC-RETRY-2: POST retry non-FAILED job (PENDING or COMPLETED) → 422 IMPORT_JOB_NOT_RETRYABLE.</li>
 *   <li>AC-RETRY-3: POST retry wrong owner → 403 FORBIDDEN.</li>
 * </ul>
 *
 * <p>Infrastructure: Testcontainers Postgres + Flyway (real schema), RSA keypair generated at
 * runtime, NO MinIO / WireMock (jobs seeded directly via ImportJobRepository).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"app.messaging.outbox.enabled=false", "app.csv.reaper.enabled=false"})
class StatusRetryIntegrationTest {

    // -----------------------------------------------------------------------
    // Shared IDs
    // -----------------------------------------------------------------------

    static final UUID ORG_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID OTHER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID CONCERT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_ingestion_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    // -----------------------------------------------------------------------
    // RSA keypair generated at runtime
    // -----------------------------------------------------------------------

    static KeyPair PRIMARY_PAIR;
    static String publicKeyFilePath;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            PRIMARY_PAIR = gen.generateKeyPair();

            Path tmp = Files.createTempFile("jwt-status-retry-test-public-", ".pem");
            byte[] encoded = PRIMARY_PAIR.getPublic().getEncoded();
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(tmp, pem);
            publicKeyFilePath = "file:" + tmp.toAbsolutePath().toString().replace("\\", "/");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA keypair for test", e);
        }
    }

    // -----------------------------------------------------------------------
    // @DynamicPropertySource
    // -----------------------------------------------------------------------

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Postgres
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

        // MinIO — point at localhost with dummy creds (no uploads in these tests)
        registry.add("app.object-storage.endpoint", () -> "http://localhost:9999");
        registry.add("app.object-storage.access-key", () -> "dummy");
        registry.add("app.object-storage.secret-key", () -> "dummydummy");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");

        // Event service — point at localhost (not called in these tests)
        registry.add("app.event.base-url", () -> "http://localhost:9998");

        // JWT public key — runtime-generated
        registry.add("app.jwt.public-key", () -> publicKeyFilePath);
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ImportJobRepository importJobRepository;

    @Autowired
    VipGuestStagingRepository stagingRepository;

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeEach
    void cleanDb() {
        // delete staging rows first (FK → import_jobs)
        stagingRepository.deleteAll();
        importJobRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String buildToken(String sub, List<String> roles) {
        return Jwts.builder()
                .subject(sub)
                .issuer("tickefy-auth-service")
                .claim("roles", roles)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(PRIMARY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private ImportJobEntity seedJob(UUID organizerId, String status) {
        ImportJobEntity job = ImportJobEntity.builder()
                .id(UUID.randomUUID())
                .concertId(CONCERT_ID)
                .organizerId(organizerId)
                .source("UPLOAD")
                .objectKey("csv-imports/test-" + UUID.randomUUID() + ".csv")
                .status(status)
                .totalRows(0)
                .successRows(0)
                .failedRows(0)
                .duplicateRows(0)
                .attemptCount(0)
                .build();
        return importJobRepository.save(job);
    }

    private VipGuestStagingEntity seedStagingRow(UUID importJobId, String email) {
        VipGuestStagingEntity row = VipGuestStagingEntity.builder()
                .id(UUID.randomUUID())
                .importJobId(importJobId)
                .concertId(CONCERT_ID)
                .email(email)
                .lineNumber(2)
                .build();
        return stagingRepository.save(row);
    }

    // -----------------------------------------------------------------------
    // AC-STATUS-1: GET status PENDING job owned by caller → 200 with correct shape
    // -----------------------------------------------------------------------

    @Test
    void acStatus1_getPendingJobOwnedByCaller_returns200WithCorrectShape() throws Exception {
        ImportJobEntity job = seedJob(ORG_ID, "PENDING");
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(get("/api/admin/csv-import/" + job.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.importJobId").value(job.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.summary.totalRows").value(0))
                .andExpect(jsonPath("$.data.errorRows").isArray())
                .andExpect(jsonPath("$.data.errorRows").isEmpty());
    }

    // -----------------------------------------------------------------------
    // AC-STATUS-2: GET status missing UUID → 404 IMPORT_JOB_NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    void acStatus2_getMissingJob_returns404ImportJobNotFound() throws Exception {
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));
        UUID missingId = UUID.randomUUID();

        mockMvc.perform(get("/api/admin/csv-import/" + missingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("IMPORT_JOB_NOT_FOUND"));
    }

    // -----------------------------------------------------------------------
    // AC-STATUS-3a: GET status wrong owner (organizer) → 403 FORBIDDEN
    // -----------------------------------------------------------------------

    @Test
    void acStatus3a_getStatusWrongOwnerOrganizer_returns403() throws Exception {
        ImportJobEntity job = seedJob(ORG_ID, "PENDING");
        // OTHER_ID is NOT the owner
        String token = buildToken(OTHER_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(get("/api/admin/csv-import/" + job.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // -----------------------------------------------------------------------
    // AC-STATUS-3b: ADMIN token (sub != owner) → 200 (admin bypass ownership)
    // -----------------------------------------------------------------------

    @Test
    void acStatus3b_getStatusWrongOwnerButAdmin_returns200() throws Exception {
        ImportJobEntity job = seedJob(ORG_ID, "PENDING");
        // OTHER_ID is NOT the owner but has ADMIN role
        String adminToken = buildToken(OTHER_ID.toString(), List.of("ADMIN"));

        mockMvc.perform(get("/api/admin/csv-import/" + job.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.importJobId").value(job.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // -----------------------------------------------------------------------
    // AC-RETRY-1: POST retry FAILED job → 200 status=PENDING; staging cleared; DB re-fetched PENDING
    // -----------------------------------------------------------------------

    @Test
    void acRetry1_retryFailedJob_returns200Pending_stagingCleared() throws Exception {
        ImportJobEntity job = seedJob(ORG_ID, "FAILED");
        // Seed 2 staging rows with different emails
        seedStagingRow(job.getId(), "alice@example.com");
        seedStagingRow(job.getId(), "bob@example.com");

        assertThat(stagingRepository.findByImportJobId(job.getId())).hasSize(2);

        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(post("/api/admin/csv-import/" + job.getId() + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.importJobId").value(job.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // Assert staging rows deleted
        List<VipGuestStagingEntity> remaining = stagingRepository.findByImportJobId(job.getId());
        assertThat(remaining).as("staging rows must be empty after retry").isEmpty();

        // Assert job status flipped to PENDING in DB
        ImportJobEntity refetched = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(refetched.getStatus()).as("job status must be PENDING after retry").isEqualTo("PENDING");
        assertThat(refetched.getFailureReason()).as("failureReason must be null after retry").isNull();
    }

    // -----------------------------------------------------------------------
    // AC-RETRY-2: POST retry non-FAILED job (PENDING) → 422 IMPORT_JOB_NOT_RETRYABLE
    // -----------------------------------------------------------------------

    @Test
    void acRetry2_retryNonFailedJob_pending_returns422NotRetryable() throws Exception {
        ImportJobEntity job = seedJob(ORG_ID, "PENDING");
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(post("/api/admin/csv-import/" + job.getId() + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("IMPORT_JOB_NOT_RETRYABLE"));
    }

    @Test
    void acRetry2_retryNonFailedJob_completed_returns422NotRetryable() throws Exception {
        ImportJobEntity job = seedJob(ORG_ID, "COMPLETED");
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(post("/api/admin/csv-import/" + job.getId() + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("IMPORT_JOB_NOT_RETRYABLE"));
    }

    // -----------------------------------------------------------------------
    // AC-RETRY-3: POST retry wrong owner → 403 FORBIDDEN
    // -----------------------------------------------------------------------

    @Test
    void acRetry3_retryWrongOwner_returns403() throws Exception {
        ImportJobEntity job = seedJob(ORG_ID, "FAILED");
        // OTHER_ID is NOT the owner
        String token = buildToken(OTHER_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(post("/api/admin/csv-import/" + job.getId() + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
