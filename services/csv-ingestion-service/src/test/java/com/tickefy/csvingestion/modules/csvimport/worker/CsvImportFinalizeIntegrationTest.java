package com.tickefy.csvingestion.modules.csvimport.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportErrorRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
import com.tickefy.csvingestion.modules.csvimport.service.CsvImportPersistence;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-4c: CsvImportWorker finalizeJob integration tests.
 *
 * <p>AC map:
 * <ul>
 *   <li>AC1 happy: 3 valid → vip_guests 3, COMPLETED, success=3, finishedAt set, reportKey NULL.</li>
 *   <li>AC2 partial: 3 valid + 1 bad (ratio=0.25 ≤ 0.5) → PARTIALLY_COMPLETED, vip_guests 3, reportKey set, error-report in MinIO.</li>
 *   <li>AC3 threshold: 1 valid + 3 bad (ratio=0.75 > 0.5) → FAILED, vip_guests 0, error-report uploaded, NO promote.</li>
 *   <li>AC4 boundary: 2 valid + 2 bad (ratio=0.5 NOT > 0.5) → PARTIALLY_COMPLETED, vip_guests 2.</li>
 *   <li>AC5 total0: header-only CSV → COMPLETED, success=0, vip_guests 0, no error-report, no divide-by-zero.</li>
 *   <li>AC6 idempotent: pre-existing dup row → promote inserts only new rows; second process run → no dup.</li>
 *   <li>AC7 failure-wrap: inventory 5xx → markFailed("SERVICE_UNAVAILABLE") → FAILED, vip_guests 0.</li>
 *   <li>AC8 retry-after-FAILED e2e: FAILED → resetForRetry → PENDING → worker re-run → terminal, no dup vip_guests.</li>
 *   <li>AC9 state-guard: markTerminal on already-COMPLETED → affected=0, no-op.</li>
 * </ul>
 *
 * <p>Infrastructure: Testcontainers Postgres + Flyway + MinIO. WireMock inventory-service.
 * MIRRORS CsvImportWorkerIntegrationTest (4b) setup pattern.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CsvImportFinalizeIntegrationTest {

    // -----------------------------------------------------------------------
    // Shared constants
    // -----------------------------------------------------------------------

    static final UUID ORG_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID CONCERT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID VIP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID GA_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final String BUCKET = "tickefy-csv";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-01-16T16-07-38Z";

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_finalize_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    // -----------------------------------------------------------------------
    // Testcontainers — MinIO
    // -----------------------------------------------------------------------

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer(MINIO_IMAGE)
                    .withUserName("minioadmin")
                    .withPassword("minioadmin");

    // -----------------------------------------------------------------------
    // WireMock — inventory-service stub
    // -----------------------------------------------------------------------

    static WireMockServer wireMock;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Pre-create MinIO bucket
        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    // -----------------------------------------------------------------------
    // @DynamicPropertySource — mirrors CsvImportWorkerIntegrationTest
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

        // MinIO
        registry.add("app.object-storage.endpoint", MINIO::getS3URL);
        registry.add("app.object-storage.access-key", MINIO::getUserName);
        registry.add("app.object-storage.secret-key", MINIO::getPassword);
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> BUCKET);

        // WireMock (inventory-service) — dynamic port
        registry.add("app.inventory.base-url", () -> "http://localhost:" + wireMock.port());

        // CB: shrink thresholds so single call propagates immediately (no open-state blocking)
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.sliding-window-size",
                () -> "10");
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.minimum-number-of-calls",
                () -> "10");
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.failure-rate-threshold",
                () -> "100");

        // Event-service — not called by worker
        registry.add("app.event.base-url", () -> "http://localhost:19997");

        // JWT — not needed by worker but SecurityConfig loads
        registry.add("app.jwt.public-key", () -> "classpath:keys/jwt-dev-public.pem");
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");

        // Worker auto-trigger OFF — manual control
        registry.add("app.csv.worker.auto-trigger", () -> "false");

        // Small batch size to also test multi-batch path for large tests
        registry.add("app.csv.batch-size", () -> "500");

        // Error threshold 0.5 (default, explicit for clarity)
        registry.add("app.csv.error-threshold", () -> "0.5");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    CsvImportWorker worker;

    @Autowired
    CsvImportPersistence persistence;

    @Autowired
    ImportJobRepository importJobRepository;

    @Autowired
    VipGuestStagingRepository stagingRepository;

    @Autowired
    ImportErrorRepository errorRepository;

    @Autowired
    VipGuestRepository vipGuestRepository;

    @Autowired
    ObjectStorageClient objectStorage;

    // -----------------------------------------------------------------------
    // Test setup — clean DB between tests to avoid cross-test pollution
    // -----------------------------------------------------------------------

    @BeforeEach
    void cleanDb() {
        // FK order: errors + staging → jobs → vip_guests (no FK from vip_guests to jobs)
        errorRepository.deleteAll();
        stagingRepository.deleteAll();
        importJobRepository.deleteAll();
        vipGuestRepository.deleteAll();
        wireMock.resetAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Seed a PENDING import job directly via JPA. */
    private ImportJobEntity seedPendingJob(UUID concertId, String objectKey) {
        ImportJobEntity job = ImportJobEntity.builder()
                .id(UUID.randomUUID())
                .concertId(concertId)
                .organizerId(ORG_ID)
                .source("UPLOAD")
                .objectKey(objectKey)
                .status("PENDING")
                .totalRows(0)
                .successRows(0)
                .failedRows(0)
                .duplicateRows(0)
                .attemptCount(0)
                .build();
        return importJobRepository.save(job);
    }

    /** Upload CSV bytes to MinIO via ObjectStorageClient. */
    private void uploadCsv(String objectKey, byte[] csvBytes) {
        objectStorage.putObject(
                objectKey, new ByteArrayInputStream(csvBytes), csvBytes.length, "text/csv");
    }

    /** Stub inventory WireMock → 200 with VIP + GA ticket types for the given concertId. */
    private void stubInventoryOk(UUID concertId) {
        String body = """
                {
                  "success": true,
                  "data": [
                    {"id": "%s", "name": "VIP", "price": 500000},
                    {"id": "%s", "name": "GA",  "price": 200000}
                  ],
                  "error": null,
                  "requestId": "req-inv-finalize-test"
                }
                """.formatted(VIP_ID, GA_ID);
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + concertId + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(body)));
    }

    /** Stub inventory WireMock → 503 to trigger fallback/failure-wrap. */
    private void stubInventory503(UUID concertId) {
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + concertId + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(503)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"success\":false,\"error\":{\"code\":\"SERVICE_UNAVAILABLE\"}}")));
    }

    /** Unique object key per test to avoid MinIO state pollution. */
    private String uniqueKey() {
        return "csv-imports/" + UUID.randomUUID() + ".csv";
    }

    /**
     * Trigger worker async and await until the job reaches a terminal status
     * (COMPLETED / PARTIALLY_COMPLETED / FAILED) OR finishedAt is set.
     * Timeout = 15s. Returns the reloaded terminal job entity.
     */
    private ImportJobEntity triggerAndAwaitTerminal(UUID jobId) {
        worker.process(jobId);
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    ImportJobEntity j = importJobRepository.findById(jobId).orElse(null);
                    if (j == null) return false;
                    return j.getFinishedAt() != null
                            || "COMPLETED".equals(j.getStatus())
                            || "PARTIALLY_COMPLETED".equals(j.getStatus())
                            || "FAILED".equals(j.getStatus());
                });
        return importJobRepository.findById(jobId).orElseThrow();
    }

    /**
     * Read a MinIO object as a UTF-8 string using the raw MinioClient.
     * We need a direct MinioClient because ObjectStorageClient only exposes getObject(key)
     * which returns InputStream — that is fine, we just read it.
     */
    private String readObjectAsString(String key) {
        try (var stream = objectStorage.getObject(key);
             var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read object: " + key, e);
        }
    }

    /** Seed a vip_guest row directly (to pre-populate duplicates for AC6). */
    private VipGuestEntity seedVipGuest(UUID concertId, String email) {
        VipGuestEntity g = VipGuestEntity.builder()
                .id(UUID.randomUUID())
                .concertId(concertId)
                .email(email)
                .fullName("Pre-Existing")
                .ticketTypeId(VIP_ID)
                .ticketTypeName("VIP")
                .build();
        return vipGuestRepository.save(g);
    }

    // -----------------------------------------------------------------------
    // AC1: happy path — 3 valid rows, 0 errors → COMPLETED, vip_guests=3, reportKey=NULL
    // -----------------------------------------------------------------------

    @Test
    void ac1_happy_3valid_completed_vipGuests3_noReport() {
        stubInventoryOk(CONCERT_ID);
        String key = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,bob@example.com,GA\n"
                + "Carol,carol@example.com,VIP\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        ImportJobEntity result = triggerAndAwaitTerminal(job.getId());

        // Status
        assertThat(result.getStatus()).as("AC1: COMPLETED").isEqualTo("COMPLETED");
        assertThat(result.getSuccessRows()).as("AC1: success=3").isEqualTo(3);
        assertThat(result.getFinishedAt()).as("AC1: finishedAt set").isNotNull();
        assertThat(result.getErrorReportObjectKey()).as("AC1: reportKey NULL (0 errors)").isNull();

        // vip_guests promoted
        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC1: 3 vip_guests rows").hasSize(3);

        // No error-report object in MinIO
        assertThat(objectStorage.exists("error-reports/" + job.getId() + ".csv"))
                .as("AC1: no error-report object in MinIO").isFalse();
    }

    // -----------------------------------------------------------------------
    // AC2: partial — 3 valid + 1 unknown ticket type (ratio=1/4=0.25 ≤ 0.5)
    //       → PARTIALLY_COMPLETED, vip_guests=3, reportKey set, MinIO has error CSV
    // -----------------------------------------------------------------------

    @Test
    void ac2_partial_3valid1bad_ratio025_partiallyCompleted_report() {
        stubInventoryOk(CONCERT_ID);
        String key = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,bob@example.com,GA\n"
                + "Carol,carol@example.com,VIP\n"
                + "Dave,dave@example.com,UNKNOWN_TYPE\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        ImportJobEntity result = triggerAndAwaitTerminal(job.getId());

        assertThat(result.getStatus()).as("AC2: PARTIALLY_COMPLETED").isEqualTo("PARTIALLY_COMPLETED");
        assertThat(result.getSuccessRows()).as("AC2: success=3").isEqualTo(3);
        assertThat(result.getFinishedAt()).as("AC2: finishedAt set").isNotNull();
        assertThat(result.getErrorReportObjectKey())
                .as("AC2: reportKey set (errors > 0)")
                .isNotNull()
                .isEqualTo("error-reports/" + job.getId() + ".csv");

        // vip_guests = 3 promoted
        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC2: 3 vip_guests").hasSize(3);

        // MinIO has error-report object
        assertThat(objectStorage.exists("error-reports/" + job.getId() + ".csv"))
                .as("AC2: error-report exists in MinIO").isTrue();

        // error-report CSV content: header + 1 row
        String reportContent = readObjectAsString("error-reports/" + job.getId() + ".csv");
        assertThat(reportContent).as("AC2: report has header line").contains("line_number,raw_data,reason");
        assertThat(reportContent).as("AC2: report has TICKET_TYPE_NOT_FOUND").contains("TICKET_TYPE_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC3: threshold exceeded — 1 valid + 3 bad (ratio=0.75 > 0.5)
    //       → FAILED, vip_guests=0 (NO promote), error-report uploaded
    // -----------------------------------------------------------------------

    @Test
    void ac3_thresholdExceeded_1valid3bad_ratio075_failed_noPromote() {
        stubInventoryOk(CONCERT_ID);
        String key = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bad1,notanemail,GA\n"
                + "Bad2,,VIP\n"
                + "Bad3,bad3@example.com,UNKNOWN_TYPE\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        ImportJobEntity result = triggerAndAwaitTerminal(job.getId());

        // FAILED via finalize (threshold), not via markFailed (exception)
        assertThat(result.getStatus()).as("AC3: FAILED (threshold exceeded)").isEqualTo("FAILED");
        assertThat(result.getSuccessRows()).as("AC3: success=0 (no promote on threshold-fail)").isEqualTo(0);
        assertThat(result.getFinishedAt()).as("AC3: finishedAt set").isNotNull();

        // NO vip_guests promoted
        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC3: vip_guests=0 (promote skipped)").isEmpty();

        // Error-report uploaded (errorCount > 0 before threshold check)
        assertThat(result.getErrorReportObjectKey())
                .as("AC3: reportKey set").isNotNull();
        assertThat(objectStorage.exists("error-reports/" + job.getId() + ".csv"))
                .as("AC3: error-report in MinIO").isTrue();
    }

    // -----------------------------------------------------------------------
    // AC4: boundary — 2 valid + 2 bad (ratio=0.5 NOT > 0.5 → PARTIALLY_COMPLETED)
    // -----------------------------------------------------------------------

    @Test
    void ac4_boundary_2valid2bad_ratio05_notFailed_partiallyCompleted() {
        stubInventoryOk(CONCERT_ID);
        String key = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,bob@example.com,GA\n"
                + "Bad1,notanemail,GA\n"
                + "Bad2,,VIP\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        ImportJobEntity result = triggerAndAwaitTerminal(job.getId());

        // 0.5 is NOT > 0.5, so must NOT be FAILED
        assertThat(result.getStatus())
                .as("AC4: boundary 0.5 must not be FAILED (ratio > threshold, strictly greater)")
                .isEqualTo("PARTIALLY_COMPLETED");
        assertThat(result.getSuccessRows()).as("AC4: success=2").isEqualTo(2);

        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC4: vip_guests=2 (promote happened)").hasSize(2);
    }

    // -----------------------------------------------------------------------
    // AC5: total==0 — header-only CSV → COMPLETED, success=0, no error-report, no divide-by-zero
    // -----------------------------------------------------------------------

    @Test
    void ac5_total0_headerOnly_completed_success0_noReport() {
        stubInventoryOk(CONCERT_ID);
        String key = uniqueKey();
        byte[] csv = "name,email,ticket_type\n".getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        ImportJobEntity result = triggerAndAwaitTerminal(job.getId());

        assertThat(result.getStatus()).as("AC5: COMPLETED (empty file)").isEqualTo("COMPLETED");
        assertThat(result.getSuccessRows()).as("AC5: success=0").isEqualTo(0);
        assertThat(result.getTotalRows()).as("AC5: total=0").isEqualTo(0);
        assertThat(result.getFinishedAt()).as("AC5: finishedAt set").isNotNull();
        assertThat(result.getErrorReportObjectKey()).as("AC5: reportKey NULL").isNull();

        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC5: vip_guests=0").isEmpty();

        // No error-report in MinIO
        assertThat(objectStorage.exists("error-reports/" + job.getId() + ".csv"))
                .as("AC5: no error-report in MinIO").isFalse();
    }

    // -----------------------------------------------------------------------
    // AC6: idempotent promote — pre-existing dup; promote inserts only new rows;
    //       second run of same job → vip_guests not increased
    // -----------------------------------------------------------------------

    @Test
    void ac6_idempotent_dupEmail_promoteInsertsOnlyNew_secondRunNoIncrease() {
        stubInventoryOk(CONCERT_ID);

        // Pre-seed 1 existing vip_guest (dup@x.com already imported)
        seedVipGuest(CONCERT_ID, "dup@x.com");
        assertThat(vipGuestRepository.findByConcertId(CONCERT_ID)).hasSize(1);

        String key = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Dup,dup@x.com,VIP\n"
                + "New1,new1@x.com,VIP\n"
                + "New2,new2@x.com,GA\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        ImportJobEntity result = triggerAndAwaitTerminal(job.getId());

        // Should be COMPLETED (all 3 staged, 0 errors; dup is in vip_guests not in staging)
        // Staging dedup is per-file, dup@x.com is NOT a duplicate within this CSV file.
        // ON CONFLICT DO NOTHING on vip_guests → promotes 2 new rows only.
        assertThat(result.getStatus()).as("AC6: job COMPLETED").isEqualTo("COMPLETED");
        assertThat(result.getSuccessRows())
                .as("AC6: promote returned 2 new rows (dup@x.com skipped by ON CONFLICT)")
                .isEqualTo(2);

        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC6: total vip_guests = 1 pre-existing + 2 new = 3").hasSize(3);

        // Part 2: calling markTerminal again (state-guard: job already terminal → no-op)
        // We verify via persistence bean directly (AC9 overlaps here)
        boolean applied = persistence.markTerminal(job.getId(), "COMPLETED", 99, null);
        assertThat(applied).as("AC6: second markTerminal is no-op (not in PROCESSING)").isFalse();

        // vip_guests count must not change
        List<VipGuestEntity> guestsAfterRetry = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guestsAfterRetry).as("AC6: vip_guests unchanged after no-op").hasSize(3);
    }

    // -----------------------------------------------------------------------
    // AC7: failure-wrap — inventory 5xx → ingest throws ApiException(SERVICE_UNAVAILABLE)
    //       → markFailed("SERVICE_UNAVAILABLE"), status=FAILED, vip_guests=0
    // -----------------------------------------------------------------------

    @Test
    void ac7_failureWrap_inventory5xx_markFailed_serviceUnavailable() {
        stubInventory503(CONCERT_ID);

        String key = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);

        // Worker fires async; 503 → InventoryUnavailableException → ApiException → markFailed
        worker.process(job.getId());
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    ImportJobEntity j = importJobRepository.findById(job.getId()).orElse(null);
                    if (j == null) return false;
                    return "FAILED".equals(j.getStatus()) && j.getFinishedAt() != null;
                });

        ImportJobEntity result = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(result.getStatus()).as("AC7: status=FAILED").isEqualTo("FAILED");
        assertThat(result.getFailureReason())
                .as("AC7: failureReason=SERVICE_UNAVAILABLE (PII-free §15)")
                .isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(result.getFinishedAt()).as("AC7: finishedAt set").isNotNull();

        // No vip_guests inserted (ingest never completed)
        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC7: vip_guests=0").isEmpty();

        // No staging rows either
        assertThat(stagingRepository.findByImportJobId(job.getId()))
                .as("AC7: no staging rows").isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC8: retry-after-FAILED e2e — FAILED (threshold) → resetForRetry → PENDING →
    //       worker re-run → terminal again; vip_guests idempotent (no dup)
    // -----------------------------------------------------------------------

    @Test
    void ac8_retryAfterFailed_e2e_noVipGuestDup() {
        stubInventoryOk(CONCERT_ID);

        String key = uniqueKey();
        // 1 valid + 3 bad → ratio=0.75 > 0.5 → FAILED (threshold)
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bad1,notanemail,GA\n"
                + "Bad2,,VIP\n"
                + "Bad3,bad3@example.com,UNKNOWN_TYPE\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);

        // First run → FAILED (threshold)
        ImportJobEntity afterFirst = triggerAndAwaitTerminal(job.getId());
        assertThat(afterFirst.getStatus()).as("AC8: first run FAILED").isEqualTo("FAILED");
        assertThat(vipGuestRepository.findByConcertId(CONCERT_ID))
                .as("AC8: no vip_guests after first FAILED").isEmpty();

        // Reset for retry: clear staging + flip to PENDING
        persistence.resetForRetry(job.getId());

        // Verify staging cleared and status=PENDING
        assertThat(stagingRepository.findByImportJobId(job.getId()))
                .as("AC8: staging cleared after resetForRetry").isEmpty();
        ImportJobEntity afterReset = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterReset.getStatus()).as("AC8: status PENDING after reset").isEqualTo("PENDING");

        // Second run with same CSV → FAILED again (same ratio)
        ImportJobEntity afterSecond = triggerAndAwaitTerminal(job.getId());
        assertThat(afterSecond.getStatus()).as("AC8: second run also FAILED").isEqualTo("FAILED");

        // vip_guests still 0 (promote never called because threshold exceeded both times)
        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC8: vip_guests=0 after both runs (no dup)").isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC8b: retry with a corrected CSV (below threshold) → PARTIALLY_COMPLETED,
    //        vip_guests promoted; second retry → ON CONFLICT DO NOTHING → no dup
    // -----------------------------------------------------------------------

    @Test
    void ac8b_retryWithCorrectCsv_promotes_thenSecondRetryNoVipGuestDup() {
        stubInventoryOk(CONCERT_ID);

        // First attempt: threshold-fail CSV
        String key = uniqueKey();
        byte[] badCsv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bad1,notanemail,GA\n"
                + "Bad2,,VIP\n"
                + "Bad3,bad3@example.com,UNKNOWN_TYPE\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, badCsv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        triggerAndAwaitTerminal(job.getId());
        assertThat(importJobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo("FAILED");

        // Reset for retry — then overwrite the object key with a better CSV
        persistence.resetForRetry(job.getId());

        // Upload a better CSV to the SAME object key (just overwrite in MinIO)
        byte[] goodCsv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,bob@example.com,GA\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, goodCsv);  // overwrites; job.objectKey unchanged

        // Second run → COMPLETED
        ImportJobEntity afterRetry = triggerAndAwaitTerminal(job.getId());
        assertThat(afterRetry.getStatus()).as("AC8b: COMPLETED after retry with good CSV").isEqualTo("COMPLETED");
        assertThat(afterRetry.getSuccessRows()).as("AC8b: success=2").isEqualTo(2);

        List<VipGuestEntity> guests = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guests).as("AC8b: 2 vip_guests after retry").hasSize(2);

        // Third run (second retry) — simulate calling resetForRetry + re-running
        persistence.resetForRetry(job.getId());
        ImportJobEntity afterSecondRetry = triggerAndAwaitTerminal(job.getId());
        assertThat(afterSecondRetry.getStatus())
                .as("AC8b: COMPLETED on second retry").isEqualTo("COMPLETED");

        List<VipGuestEntity> guestsAfterSecond = vipGuestRepository.findByConcertId(CONCERT_ID);
        assertThat(guestsAfterSecond)
                .as("AC8b: vip_guests still 2 (ON CONFLICT DO NOTHING — no dup)")
                .hasSize(2);
        // promote returns 0 new rows on second retry
        assertThat(afterSecondRetry.getSuccessRows())
                .as("AC8b: successRows=0 (all conflict, no new rows)")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // AC9: state-guard terminal atomic — job already COMPLETED → markTerminal → affected=0
    // -----------------------------------------------------------------------

    @Test
    void ac9_stateGuardTerminal_alreadyCompleted_markTerminalIsNoop() {
        stubInventoryOk(CONCERT_ID);
        String key = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(key, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, key);
        ImportJobEntity result = triggerAndAwaitTerminal(job.getId());
        assertThat(result.getStatus()).as("AC9: setup — job COMPLETED").isEqualTo("COMPLETED");
        assertThat(result.getSuccessRows()).isEqualTo(1);

        // Call markTerminal again with different values — must be no-op (WHERE status='PROCESSING' fails)
        boolean applied = persistence.markTerminal(job.getId(), "FAILED", 99, "fake-report.csv");
        assertThat(applied).as("AC9: markTerminal on COMPLETED job returns false (no-op)").isFalse();

        // Status and successRows must NOT be changed
        ImportJobEntity unchanged = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(unchanged.getStatus())
                .as("AC9: status still COMPLETED after no-op markTerminal").isEqualTo("COMPLETED");
        assertThat(unchanged.getSuccessRows())
                .as("AC9: successRows still 1 (no-op did not clobber)").isEqualTo(1);
        assertThat(unchanged.getErrorReportObjectKey())
                .as("AC9: reportKey still null (no-op did not set fake-report.csv)").isNull();
    }
}
