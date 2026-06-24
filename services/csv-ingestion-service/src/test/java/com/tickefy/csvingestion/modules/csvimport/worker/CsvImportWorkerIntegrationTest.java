package com.tickefy.csvingestion.modules.csvimport.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportErrorRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-4b: CsvImportWorker integration tests.
 *
 * <p>AC map:
 * <ul>
 *   <li>AC1-all-valid: 3 valid rows → staging 3, errors 0, counters total=3/staged=3/failed=0/dup=0, status PROCESSING.</li>
 *   <li>AC2-mixed: 1 valid + INVALID_EMAIL + MISSING_FIELD + TICKET_TYPE_NOT_FOUND → staging 1, errors 3, skip-continue.</li>
 *   <li>AC3-lineNumber: error at data row k (1-indexed from header=1) → lineNumber == k+1.</li>
 *   <li>AC4-dedup: same email case/space variants → staging 1, DUPLICATE_ROW error 1, duplicate counter=1.</li>
 *   <li>AC5-case-insensitive: "vip"/"VIP" resolve to VIP_ID; "XYZ" → TICKET_TYPE_NOT_FOUND.</li>
 *   <li>AC6-atomic-claim: job already PROCESSING → worker.process skips (staging stays 0, no double).</li>
 *   <li>AC7-streaming: ~2000 rows → staging 2000, batch flush across multiple batches.</li>
 * </ul>
 *
 * <p>Infrastructure: Testcontainers Postgres + Flyway (real schema), Testcontainers MinIO,
 * WireMock (inventory-service stub).
 * Worker is called via the Spring bean (through @Async proxy) and Awaitility polls until
 * job counters update (updateCounters fires after parsing completes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CsvImportWorkerIntegrationTest {

    // -----------------------------------------------------------------------
    // Shared constants
    // -----------------------------------------------------------------------

    static final UUID ORG_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID CONCERT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID VIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID GA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String BUCKET = "tickefy-csv";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-01-16T16-07-38Z";

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_ingestion_worker_test")
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
    // WireMock (inventory-service stub) — static lifecycle
    // -----------------------------------------------------------------------

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() throws Exception {
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

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
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

        // MinIO
        registry.add("app.object-storage.endpoint", MINIO::getS3URL);
        registry.add("app.object-storage.access-key", MINIO::getUserName);
        registry.add("app.object-storage.secret-key", MINIO::getPassword);
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> BUCKET);

        // WireMock (inventory-service)
        registry.add("app.inventory.base-url", () -> "http://localhost:" + wireMock.port());

        // Shrink CB so it doesn't affect normal tests
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.sliding-window-size",
                () -> "10");
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.minimum-number-of-calls",
                () -> "10");
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.failure-rate-threshold",
                () -> "100");

        // Event-service — not called by worker; point at dummy
        registry.add("app.event.base-url", () -> "http://localhost:19998");

        // JWT — worker doesn't need JWT but context loads SecurityConfig
        registry.add("app.jwt.public-key", () -> "classpath:keys/jwt-dev-public.pem");
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");

        // Worker auto-trigger OFF (context spec §6.12): we trigger manually
        registry.add("app.csv.worker.auto-trigger", () -> "false");

        // Small batch size so AC7 exercises multi-batch flush (2000 rows / 500 = 4 batches)
        registry.add("app.csv.batch-size", () -> "500");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    CsvImportWorker worker;

    @Autowired
    ImportJobRepository importJobRepository;

    @Autowired
    VipGuestStagingRepository stagingRepository;

    @Autowired
    ImportErrorRepository errorRepository;

    @Autowired
    ObjectStorageClient objectStorage;

    @Autowired
    VipGuestRepository vipGuestRepository;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Seed a PENDING import job directly via JPA (WorkerTrigger absent — auto-trigger=false). */
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

    /** Seed a job with an arbitrary status (e.g., PROCESSING for atomic-claim test). */
    private ImportJobEntity seedJobWithStatus(UUID concertId, String objectKey, String status) {
        ImportJobEntity job = ImportJobEntity.builder()
                .id(UUID.randomUUID())
                .concertId(concertId)
                .organizerId(ORG_ID)
                .source("UPLOAD")
                .objectKey(objectKey)
                .status(status)
                .totalRows(0)
                .successRows(0)
                .failedRows(0)
                .duplicateRows(0)
                .attemptCount(0)
                .build();
        return importJobRepository.save(job);
    }

    /** Upload CSV bytes to MinIO. */
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
                  "requestId": "req-inv-test"
                }
                """.formatted(VIP_ID, GA_ID);
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + concertId + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(body)));
    }

    /** Unique object key for each test to avoid cross-test MinIO state pollution. */
    private String uniqueKey() {
        return "csv-imports/" + UUID.randomUUID() + ".csv";
    }

    /**
     * Trigger worker async and await until the job's totalRows counter gets updated (updateCounters
     * fires at the very end of a successful parse run). Timeout = 10s (generous for CI).
     * After this method returns the DB counters are stable.
     */
    private void triggerAndWait(UUID jobId, int expectedTotal) {
        worker.process(jobId); // @Async proxy dispatches to csvWorkerExecutor thread
        // Wait for terminal (finishedAt set) — process() now ingests AND finalizes (T4c), so polling
        // totalRows alone could read pre-finalize PROCESSING state.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    ImportJobEntity job = importJobRepository.findById(jobId).orElse(null);
                    return job != null && job.getTotalRows() == expectedTotal && job.getFinishedAt() != null;
                });
    }

    /**
     * For the atomic-claim test we need to detect "worker skipped" (totalRows stays 0) not
     * "worker ran". We wait until status stays unchanged for at least 500ms — or simply wait a
     * fixed small window, since the worker should skip almost immediately (log + return).
     */
    private void triggerAndWaitSkip(UUID jobId) {
        worker.process(jobId);
        // Awaitility: wait 2s to confirm totalRows remains 0 (no parse occurred)
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(3))
                .until(() -> {
                    ImportJobEntity job = importJobRepository.findById(jobId).orElse(null);
                    return job != null && job.getTotalRows() == 0;
                });
    }

    @BeforeEach
    void cleanDb() {
        vipGuestRepository.deleteAll();
        errorRepository.deleteAll();
        stagingRepository.deleteAll();
        importJobRepository.deleteAll();
        wireMock.resetAll();
    }

    @Autowired
    @Qualifier("csvWorkerExecutor")
    Executor workerExecutor;

    /** Drain in-flight @Async workers before the context/DB pool tears down (no leak past test). */
    @AfterEach
    void drainWorker() {
        if (workerExecutor instanceof ThreadPoolTaskExecutor tpe) {
            await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> tpe.getThreadPoolExecutor().getActiveCount() == 0
                            && tpe.getThreadPoolExecutor().getQueue().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // AC1: all-valid — 3 rows, status stays PROCESSING, counters correct
    // -----------------------------------------------------------------------

    @Test
    void ac1_allValid_3rows_staging3_errors0_countersCorrect_statusProcessing() {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,bob@example.com,GA\n"
                + "Carol,carol@example.com,VIP\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, objectKey);
        triggerAndWait(job.getId(), 3);

        // DB assertions — reload from repo
        ImportJobEntity updated = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getStatus()).as("AC1: 0 errors -> COMPLETED after finalize").isEqualTo("COMPLETED");
        assertThat(updated.getTotalRows()).as("AC1: total=3").isEqualTo(3);
        assertThat(updated.getSuccessRows()).as("AC1: staged=3").isEqualTo(3);
        assertThat(updated.getFailedRows()).as("AC1: failed=0").isEqualTo(0);
        assertThat(updated.getDuplicateRows()).as("AC1: duplicate=0").isEqualTo(0);

        List<VipGuestStagingEntity> staging = stagingRepository.findByImportJobId(job.getId());
        assertThat(staging).as("AC1: 3 staging rows").hasSize(3);
        assertThat(staging).allMatch(s -> s.getImportJobId().equals(job.getId()));

        List<ImportErrorEntity> errors = errorRepository.findByImportJobId(job.getId());
        assertThat(errors).as("AC1: 0 errors").isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC2: mixed — 1 valid + INVALID_EMAIL + MISSING_FIELD + TICKET_TYPE_NOT_FOUND
    //              skip-continue: valid row still staged
    // -----------------------------------------------------------------------

    @Test
    void ac2_mixed_skipContinue_staging1_errors3_failed3() {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();
        // data rows:
        //   row 1 (line 2): valid → stage
        //   row 2 (line 3): invalid email
        //   row 3 (line 4): missing email field (blank)
        //   row 4 (line 5): unknown ticket type XYZ
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,notanemail,GA\n"
                + "Carol,,VIP\n"
                + "Dave,dave@example.com,XYZ\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, objectKey);
        triggerAndWait(job.getId(), 4);

        ImportJobEntity updated = importJobRepository.findById(job.getId()).orElseThrow();
        // 3 errors / 4 total = 0.75 > 0.5 threshold -> FAILED, no promote (success=0)
        assertThat(updated.getStatus()).as("AC2: ratio 0.75 > 0.5 -> FAILED").isEqualTo("FAILED");
        assertThat(updated.getTotalRows()).as("AC2: total=4").isEqualTo(4);
        assertThat(updated.getSuccessRows()).as("AC2: FAILED -> no promote, success=0").isEqualTo(0);
        assertThat(updated.getFailedRows()).as("AC2: failed=3").isEqualTo(3);
        assertThat(updated.getDuplicateRows()).as("AC2: duplicate=0").isEqualTo(0);

        List<VipGuestStagingEntity> staging = stagingRepository.findByImportJobId(job.getId());
        assertThat(staging).as("AC2: skip-continue — valid row still staged").hasSize(1);
        assertThat(staging.get(0).getEmail()).as("AC2: alice staged").isEqualTo("alice@example.com");

        List<ImportErrorEntity> errors = errorRepository.findByImportJobId(job.getId());
        assertThat(errors).as("AC2: 3 error rows").hasSize(3);

        List<String> reasons = errors.stream().map(ImportErrorEntity::getReason).toList();
        assertThat(reasons).as("AC2: INVALID_EMAIL present").contains("INVALID_EMAIL");
        assertThat(reasons).as("AC2: MISSING_FIELD present").contains("MISSING_FIELD");
        assertThat(reasons).as("AC2: TICKET_TYPE_NOT_FOUND present").contains("TICKET_TYPE_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC3: lineNumber — error at data row k → import_errors.lineNumber == k+1
    //      header = line 1; data row 2 = line 3
    // -----------------------------------------------------------------------

    @Test
    void ac3_lineNumber_errorAtDataRow2_lineNumberIs3() {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();
        // data row 1 (line 2): valid
        // data row 2 (line 3): INVALID_EMAIL — expected lineNumber = 3
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,notanemail,GA\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, objectKey);
        triggerAndWait(job.getId(), 2);

        List<ImportErrorEntity> errors = errorRepository.findByImportJobId(job.getId());
        assertThat(errors).as("AC3: exactly 1 error").hasSize(1);
        ImportErrorEntity err = errors.get(0);
        assertThat(err.getReason()).as("AC3: INVALID_EMAIL reason").isEqualTo("INVALID_EMAIL");
        // Header = line 1, first data = line 2, second data = line 3
        assertThat(err.getLineNumber()).as("AC3: lineNumber must be 3 (header=1, data-row-2=line-3)")
                .isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // AC4: dedup-in-file — same email, case/spacing variants → staging 1, DUPLICATE_ROW 1
    // -----------------------------------------------------------------------

    @Test
    void ac4_dupInFile_caseSpacingVariants_staging1_duplicateRow1() {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();
        // "A@x.com" vs " a@x.com " — both normalize to "a@x.com"
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,A@x.com,VIP\n"
                + "Bob, a@x.com ,VIP\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, objectKey);
        triggerAndWait(job.getId(), 2);

        ImportJobEntity updated = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getSuccessRows()).as("AC4: staged=1").isEqualTo(1);
        assertThat(updated.getDuplicateRows()).as("AC4: duplicate=1").isEqualTo(1);
        assertThat(updated.getFailedRows()).as("AC4: failed=0 (dup counted separately)").isEqualTo(0);

        List<VipGuestStagingEntity> staging = stagingRepository.findByImportJobId(job.getId());
        assertThat(staging).as("AC4: exactly 1 staging row").hasSize(1);
        assertThat(staging.get(0).getEmail()).as("AC4: normalized email stored")
                .isEqualTo("a@x.com");

        List<ImportErrorEntity> errors = errorRepository.findByImportJobId(job.getId());
        assertThat(errors).as("AC4: 1 DUPLICATE_ROW error").hasSize(1);
        assertThat(errors.get(0).getReason()).as("AC4: reason=DUPLICATE_ROW")
                .isEqualTo("DUPLICATE_ROW");
    }

    // -----------------------------------------------------------------------
    // AC5: resolve case-insensitive — "vip"/"VIP" → VIP_ID; "XYZ" → TICKET_TYPE_NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    void ac5_resolveCase_vipLowercase_resolvesToVipId_xyzNotFound() {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,vip\n"   // lowercase → should resolve to VIP_ID
                + "Bob,bob@example.com,VIP\n"         // exact case → should also resolve
                + "Carol,carol@example.com,XYZ\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, objectKey);
        triggerAndWait(job.getId(), 3);

        List<VipGuestStagingEntity> staging = stagingRepository.findByImportJobId(job.getId());
        assertThat(staging).as("AC5: 2 valid rows staged (vip + VIP)").hasSize(2);
        assertThat(staging).allMatch(s -> s.getTicketTypeId().equals(VIP_ID),
                "AC5: both staged rows must have ticketTypeId=VIP_ID");

        List<ImportErrorEntity> errors = errorRepository.findByImportJobId(job.getId());
        assertThat(errors).as("AC5: 1 error for XYZ").hasSize(1);
        assertThat(errors.get(0).getReason()).as("AC5: TICKET_TYPE_NOT_FOUND for XYZ")
                .isEqualTo("TICKET_TYPE_NOT_FOUND");

        ImportJobEntity updated = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getSuccessRows()).as("AC5: staged=2").isEqualTo(2);
        assertThat(updated.getFailedRows()).as("AC5: failed=1").isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC6: atomic claim — job already PROCESSING → worker.process skips, staging stays 0
    // -----------------------------------------------------------------------

    @Test
    void ac6_atomicClaim_jobAlreadyProcessing_workerSkips_staging0() {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        // Seed with status=PROCESSING (claim query only matches PENDING → will return 0)
        ImportJobEntity job = seedJobWithStatus(CONCERT_ID, objectKey, "PROCESSING");

        triggerAndWaitSkip(job.getId());

        // No staging rows created — worker returned early after failed claim
        List<VipGuestStagingEntity> staging = stagingRepository.findByImportJobId(job.getId());
        assertThat(staging).as("AC6: staging must be empty — worker skipped PROCESSING job").isEmpty();

        // Counters stay at 0 (updateCounters never called)
        ImportJobEntity updated = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getTotalRows()).as("AC6: totalRows must remain 0").isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // AC6-b: double-trigger same PENDING job → worker runs once (second claim fails)
    // -----------------------------------------------------------------------

    @Test
    void ac6b_doubleTrigger_samePendingJob_workerRunsOnce_stagingEqualsN() throws InterruptedException {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();
        byte[] csv = ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,bob@example.com,GA\n").getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, objectKey);

        // Fire both triggers — one wins the claim, other skips
        worker.process(job.getId());
        worker.process(job.getId());

        // Wait for the winning worker to finish (totalRows = 2)
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> importJobRepository.findById(job.getId())
                        .map(j -> j.getTotalRows() == 2)
                        .orElse(false));

        List<VipGuestStagingEntity> staging = stagingRepository.findByImportJobId(job.getId());
        assertThat(staging).as("AC6b: staging must have exactly 2 rows — NOT 4 (double-run)").hasSize(2);
    }

    // -----------------------------------------------------------------------
    // AC7: streaming — 2000 valid rows → staging 2000 across multiple batch flushes, no OOM
    // -----------------------------------------------------------------------

    @Test
    void ac7_streaming_2000validRows_staging2000_multipleBatches() {
        stubInventoryOk(CONCERT_ID);
        String objectKey = uniqueKey();

        StringBuilder sb = new StringBuilder("name,email,ticket_type\n");
        for (int i = 1; i <= 2000; i++) {
            sb.append("User").append(i)
                    .append(",user").append(i).append("@example.com,VIP\n");
        }
        byte[] csv = sb.toString().getBytes(StandardCharsets.UTF_8);
        uploadCsv(objectKey, csv);

        ImportJobEntity job = seedPendingJob(CONCERT_ID, objectKey);

        // Await terminal (finishedAt set) — process() ingests AND finalizes, so polling totalRows
        // alone could read the pre-finalize PROCESSING state.
        worker.process(job.getId());
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> importJobRepository.findById(job.getId())
                        .map(j -> j.getTotalRows() == 2000 && j.getFinishedAt() != null)
                        .orElse(false));

        ImportJobEntity updated = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getSuccessRows()).as("AC7: 2000 rows staged").isEqualTo(2000);
        assertThat(updated.getFailedRows()).as("AC7: 0 errors").isEqualTo(0);
        assertThat(updated.getStatus()).as("AC7: 0 errors -> COMPLETED").isEqualTo("COMPLETED");

        // Count via repo — batch-size=500 means 4 flush cycles (2000/500=4)
        List<VipGuestStagingEntity> staging = stagingRepository.findByImportJobId(job.getId());
        assertThat(staging).as("AC7: exactly 2000 staging rows in DB").hasSize(2000);
    }
}
