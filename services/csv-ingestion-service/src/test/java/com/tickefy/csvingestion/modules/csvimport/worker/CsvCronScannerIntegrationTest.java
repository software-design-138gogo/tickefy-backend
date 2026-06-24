package com.tickefy.csvingestion.modules.csvimport.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-cron-scan: CsvCronScanner integration tests.
 *
 * <p>AC map:
 * <ul>
 *   <li>AC1-pickup-e2e: valid cron-inbox/{UUID}/file.csv → scan() → import_jobs 1 row,
 *       source='CRON', organizer_id IS NULL, concert_id=UUID, status PENDING.</li>
 *   <li>AC2-dedup: scan() x2 same key → import_jobs only 1 row for that key.</li>
 *   <li>AC3-invalid-path: non-UUID segment + orphan (no second segment) → 0 jobs created.</li>
 *   <li>AC4-upload-untouched: key at root (outside cron-inbox/) → listObjects("cron-inbox/") skips it → 0 jobs.</li>
 *   <li>AC5-organizer-null: CRON job has organizer_id IS NULL (V3 CHECK allows it).</li>
 * </ul>
 *
 * <p>Infrastructure: Testcontainers Postgres + Flyway + MinIO.
 * scan() called DIRECTLY (deterministic — no @Scheduled wait).
 * app.csv.scan.enabled=true to load CsvCronScanner bean.
 * app.csv.worker.auto-trigger=false so WorkerTrigger bean is absent;
 * ObjectProvider returns empty — workerTrigger.ifAvailable() is a no-op.
 * MinIO bucket is purged @BeforeEach to prevent cross-test key pollution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = {
    "app.csv.scan.enabled=true",              // CsvCronScanner bean must load
    "app.csv.worker.auto-trigger=false",      // WorkerTrigger absent -> ObjectProvider empty -> trigger skipped
    "app.messaging.outbox.enabled=false",     // outbox drainer off — no broker needed
    "app.csv.reaper.enabled=false"            // reaper off — not needed
})
class CsvCronScannerIntegrationTest {

    // -----------------------------------------------------------------------
    // Shared constants
    // -----------------------------------------------------------------------

    private static final String BUCKET = "tickefy-csv";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-01-16T16-07-38Z";

    static final UUID CONCERT_UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    // -----------------------------------------------------------------------
    // Testcontainers
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_cron_scanner_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer(MINIO_IMAGE)
                    .withUserName("minioadmin")
                    .withPassword("minioadmin");

    // -----------------------------------------------------------------------
    // @BeforeAll — pre-create MinIO bucket (mirrors CsvImportWorkerIntegrationTest)
    // -----------------------------------------------------------------------

    @BeforeAll
    static void createBucket() throws Exception {
        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
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

        // MinIO — real container
        registry.add("app.object-storage.endpoint", MINIO::getS3URL);
        registry.add("app.object-storage.access-key", MINIO::getUserName);
        registry.add("app.object-storage.secret-key", MINIO::getPassword);
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> BUCKET);

        // External services — not called by scanner
        registry.add("app.inventory.base-url", () -> "http://localhost:19991");
        registry.add("app.event.base-url", () -> "http://localhost:19992");

        // JWT — SecurityConfig loads but no HTTP in this test
        registry.add("app.jwt.public-key", () -> "classpath:keys/jwt-dev-public.pem");
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");

        // Batch / error-threshold defaults
        registry.add("app.csv.batch-size", () -> "500");
        registry.add("app.csv.error-threshold", () -> "0.5");

        // Circuit breaker — shrink thresholds (same as worker IT)
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.sliding-window-size",
                () -> "10");
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.minimum-number-of-calls",
                () -> "10");
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.failure-rate-threshold",
                () -> "100");

        // RabbitMQ — not used; point at unreachable port; rabbit health disabled
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "15698");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    CsvCronScanner csvCronScanner;

    @Autowired
    ImportJobRepository importJobRepository;

    @Autowired
    VipGuestRepository vipGuestRepository;

    @Autowired
    ObjectStorageClient objectStorage;

    /**
     * Raw MinioClient injected to perform object deletions in @BeforeEach cleanup.
     * ObjectStorageClient interface has no deleteObject — we need MinioClient directly.
     */
    @Autowired
    MinioClient minioClient;

    @Value("${app.object-storage.bucket}")
    String bucket;

    @Autowired
    @Qualifier("csvWorkerExecutor")
    Executor workerExecutor;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Clean DB rows AND all MinIO objects before each test.
     * Shared MinIO container across tests — objects from prior tests must not pollute scan().
     */
    @BeforeEach
    void cleanAll() throws Exception {
        // DB cleanup
        vipGuestRepository.deleteAll();
        importJobRepository.deleteAll();

        // MinIO cleanup — delete all objects in the bucket (prevents cross-test scan() pollution)
        purgeAllMinioObjects();
    }

    /** Delete every object in the bucket using the raw MinioClient. */
    private void purgeAllMinioObjects() throws Exception {
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucket).recursive(true).build());
        List<String> keys = new ArrayList<>();
        for (Result<Item> r : results) {
            keys.add(r.get().objectName());
        }
        for (String key : keys) {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        }
    }

    /** Drain any in-flight @Async workers before DB pool tears down. */
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
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal valid CSV — header name,email,ticket_type + 3 data rows (worker IT format). */
    private static byte[] validCsv3Rows() {
        return ("name,email,ticket_type\n"
                + "Alice,alice@example.com,VIP\n"
                + "Bob,bob@example.com,GA\n"
                + "Carol,carol@example.com,VIP\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Put object into MinIO via ObjectStorageClient. */
    private void putObject(String key, byte[] bytes) {
        objectStorage.putObject(key, new ByteArrayInputStream(bytes), bytes.length, "text/csv");
    }

    /** Build the cron-inbox key: cron-inbox/{concertId}/{filename}. */
    private static String cronKey(UUID concertId, String filename) {
        return "cron-inbox/" + concertId + "/" + filename;
    }

    // -----------------------------------------------------------------------
    // AC1: pickup-e2e — valid cron-inbox/{UUID}/file.csv → 1 CRON job, PENDING
    // -----------------------------------------------------------------------

    @Test
    void ac1_pickup_validCronInboxKey_createsOneCronJob_pending() {
        String key = cronKey(CONCERT_UUID, "guests.csv");
        putObject(key, validCsv3Rows());

        csvCronScanner.scan();

        List<ImportJobEntity> jobs = importJobRepository.findAll();
        assertThat(jobs)
                .as("AC1: exactly 1 import_jobs row after scan()")
                .hasSize(1);

        ImportJobEntity job = jobs.get(0);
        assertThat(job.getSource())
                .as("AC1: source must be CRON")
                .isEqualTo("CRON");
        assertThat(job.getConcertId())
                .as("AC1: concertId must match the UUID parsed from cron-inbox path")
                .isEqualTo(CONCERT_UUID);
        assertThat(job.getOrganizerId())
                .as("AC1: organizer_id must be NULL for CRON jobs (V3 constraint)")
                .isNull();
        assertThat(job.getObjectKey())
                .as("AC1: objectKey must be the full cron-inbox path")
                .isEqualTo(key);
        // WorkerTrigger absent (auto-trigger=false) → job stays PENDING
        assertThat(job.getStatus())
                .as("AC1: status must be PENDING (WorkerTrigger absent)")
                .isEqualTo("PENDING");
    }

    // -----------------------------------------------------------------------
    // AC2: dedup — scan() x2 same key → only 1 job row (existsByObjectKey guards)
    // -----------------------------------------------------------------------

    @Test
    void ac2_dedup_scanTwiceSameKey_onlyOneJobCreated() {
        String key = cronKey(CONCERT_UUID, "guests_dedup.csv");
        putObject(key, validCsv3Rows());

        csvCronScanner.scan(); // first scan — creates 1 job
        csvCronScanner.scan(); // second scan — existsByObjectKey=true → skip

        List<ImportJobEntity> jobs = importJobRepository.findAll();
        assertThat(jobs)
                .as("AC2: second scan() must be skipped by dedup (existsByObjectKey=true)")
                .hasSize(1);
        assertThat(jobs.get(0).getObjectKey())
                .as("AC2: the single job must reference the correct key")
                .isEqualTo(key);
    }

    // -----------------------------------------------------------------------
    // AC3: invalid-path — non-UUID segment + orphan (no second segment) → 0 jobs
    // -----------------------------------------------------------------------

    @Test
    void ac3_invalidPath_nonUuidAndOrphan_noJobCreated() {
        // non-UUID middle segment: parseConcertId returns null → WARN + skip
        String nonUuidKey = "cron-inbox/not-a-uuid/guests.csv";
        // Only 1 segment after prefix (seg.length < 2): parseConcertId returns null → skip
        String orphanKey = "cron-inbox/orphan.csv";

        putObject(nonUuidKey, validCsv3Rows());
        putObject(orphanKey, validCsv3Rows());

        long countBefore = importJobRepository.count();
        csvCronScanner.scan();
        long countAfter = importJobRepository.count();

        assertThat(countAfter)
                .as("AC3: no jobs must be created for invalid cron-inbox paths (non-UUID + orphan)")
                .isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // AC4: upload-untouched — key at root (outside cron-inbox/) not picked up
    // -----------------------------------------------------------------------

    @Test
    void ac4_uploadUntouched_rootKeyOutsideCronInbox_notPickedUp() {
        // Put a CSV at root level — listObjects("cron-inbox/") prefix filter excludes this key
        String rootKey = UUID.randomUUID() + "-guests.csv";
        putObject(rootKey, validCsv3Rows());

        long countBefore = importJobRepository.count();
        csvCronScanner.scan();
        long countAfter = importJobRepository.count();

        assertThat(countAfter)
                .as("AC4: root-level key must NOT be picked up (listObjects('cron-inbox/') prefix filter)")
                .isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // AC5: organizer_id NULL — CRON job persisted with organizer_id=null (V3 CHECK)
    // -----------------------------------------------------------------------

    @Test
    void ac5_organizerIdNull_cronJobHasNullOrganizerId_v3CheckConstraintSatisfied() {
        String key = cronKey(CONCERT_UUID, "guests_null_organizer.csv");
        putObject(key, validCsv3Rows());

        csvCronScanner.scan();

        ImportJobEntity job = importJobRepository.findAll().stream()
                .filter(j -> key.equals(j.getObjectKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AC5: expected CRON job not found in import_jobs"));

        assertThat(job.getOrganizerId())
                .as("AC5: organizer_id must be NULL for CRON-sourced job (V3 CHECK constraint)")
                .isNull();
        assertThat(job.getSource())
                .as("AC5: source must be CRON")
                .isEqualTo("CRON");
    }
}
