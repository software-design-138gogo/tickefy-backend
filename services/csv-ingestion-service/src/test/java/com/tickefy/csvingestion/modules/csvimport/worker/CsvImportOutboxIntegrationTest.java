package com.tickefy.csvingestion.modules.csvimport.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.OutboxEntity;
import com.tickefy.csvingestion.modules.csvimport.event.CsvEvents;
import com.tickefy.csvingestion.modules.csvimport.event.EventEnvelope;
import com.tickefy.csvingestion.modules.csvimport.event.VipGuestImportCompletedPayload;
import com.tickefy.csvingestion.modules.csvimport.event.VipGuestImportFailedPayload;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.OutboxRepository;
import com.tickefy.csvingestion.modules.csvimport.service.CsvImportPersistence;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-5a: CsvImportOutboxIntegrationTest — outbox write correctness.
 *
 * <p>Tests persistence.markTerminal/markFailed directly (no MinIO/WireMock) with a seeded
 * PROCESSING job. Verifies §6.4 envelope, atomicity (no-double), and PII-free payload.
 *
 * <p>AC map:
 * <ul>
 *   <li>Case1: COMPLETED → outbox 1 row, eventType=VipGuestImportCompleted, status=PENDING, correct payload.</li>
 *   <li>Case2: PARTIALLY → outbox eventType=VipGuestImportCompleted, payload.status=PARTIALLY_COMPLETED.</li>
 *   <li>Case3: FAILED-threshold → outbox eventType=VipGuestImportFailed, failureReason=ERROR_THRESHOLD_EXCEEDED.</li>
 *   <li>Case4: exception-FAILED → markFailed → outbox eventType=VipGuestImportFailed, failureReason=SERVICE_UNAVAILABLE, job status=FAILED.</li>
 *   <li>Case5: atomic-no-double → markTerminal×2 → outbox count stays 1 (affected=0 on second call).</li>
 *   <li>Case6: no-PII → outbox payload JSON has no email/name/rawData/raw_data key.</li>
 *   <li>Case7: envelope §6.4 — payload JSON has exactly 5 top-level fields: messageId,eventType,eventVersion,occurredAt,payload.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = {"app.messaging.outbox.enabled=false", "app.csv.reaper.enabled=false", "app.csv.scan.enabled=false"})
class CsvImportOutboxIntegrationTest {

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_outbox_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    // -----------------------------------------------------------------------
    // @DynamicPropertySource — no MinIO/WireMock needed
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

        // MinIO — not used but required by context (MinioConfig loads)
        registry.add("app.object-storage.endpoint", () -> "http://localhost:19900");
        registry.add("app.object-storage.access-key", () -> "minioadmin");
        registry.add("app.object-storage.secret-key", () -> "minioadmin");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");

        // External services — not called by persistence (dummy URLs)
        registry.add("app.inventory.base-url", () -> "http://localhost:19901");
        registry.add("app.event.base-url", () -> "http://localhost:19902");

        // JWT — SecurityConfig loads but no requests in this test
        registry.add("app.jwt.public-key", () -> "classpath:keys/jwt-dev-public.pem");
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");

        // Worker auto-trigger OFF
        registry.add("app.csv.worker.auto-trigger", () -> "false");
        registry.add("app.csv.batch-size", () -> "500");
        registry.add("app.csv.error-threshold", () -> "0.5");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    CsvImportPersistence persistence;

    @Autowired
    ImportJobRepository importJobRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // Test constants
    // -----------------------------------------------------------------------

    static final UUID ORG_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID CONCERT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    // -----------------------------------------------------------------------
    // Setup — clean DB between tests
    // -----------------------------------------------------------------------

    @BeforeEach
    void cleanDb() {
        // outbox has no FK to import_jobs; delete outbox first, then jobs
        outboxRepository.deleteAll();
        importJobRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Seed a job directly with PROCESSING status so markTerminal/markFailed can transition it. */
    private ImportJobEntity seedProcessingJob() {
        ImportJobEntity job = ImportJobEntity.builder()
                .id(UUID.randomUUID())
                .concertId(CONCERT_ID)
                .organizerId(ORG_ID)
                .source("UPLOAD")
                .objectKey("csv-imports/test-" + UUID.randomUUID() + ".csv")
                .status("PROCESSING")
                .totalRows(3)
                .successRows(0)
                .failedRows(0)
                .duplicateRows(0)
                .attemptCount(1)
                .build();
        return importJobRepository.save(job);
    }

    /** Build a VipGuestImportCompleted EventEnvelope for a COMPLETED or PARTIALLY_COMPLETED job. */
    private EventEnvelope<VipGuestImportCompletedPayload> completedEvent(
            UUID jobId, String status, int total, int success, int failed, int duplicate) {
        return EventEnvelope.of(
                CsvEvents.EventType.VIP_GUEST_IMPORT_COMPLETED,
                new VipGuestImportCompletedPayload(jobId, CONCERT_ID, status, total, success, failed, duplicate));
    }

    /** Build a VipGuestImportFailed EventEnvelope. */
    private EventEnvelope<VipGuestImportFailedPayload> failedEvent(UUID jobId, String reason) {
        return EventEnvelope.of(
                CsvEvents.EventType.VIP_GUEST_IMPORT_FAILED,
                new VipGuestImportFailedPayload(jobId, CONCERT_ID, reason));
    }

    /** Parse outbox payload JSON string into a JsonNode tree for inspection. */
    private JsonNode parsePayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse outbox payload JSON: " + payloadJson, e);
        }
    }

    // -----------------------------------------------------------------------
    // Case 1: COMPLETED → outbox 1 row, eventType=VipGuestImportCompleted, status=PENDING
    // -----------------------------------------------------------------------

    @Test
    void case1_completed_outboxOneRow_eventTypeCompleted_statusPending() throws Exception {
        ImportJobEntity job = seedProcessingJob();
        UUID jobId = job.getId();

        var event = completedEvent(jobId, "COMPLETED", 3, 3, 0, 0);
        boolean applied = persistence.markTerminal(jobId, "COMPLETED", 3, null, event);

        assertThat(applied).as("Case1: markTerminal applied (PROCESSING→COMPLETED)").isTrue();

        List<OutboxEntity> rows = outboxRepository.findAll();
        assertThat(rows).as("Case1: exactly 1 outbox row").hasSize(1);

        OutboxEntity outbox = rows.get(0);
        assertThat(outbox.getAggregateId()).as("Case1: aggregateId=jobId").isEqualTo(jobId);
        assertThat(outbox.getEventType())
                .as("Case1: eventType=VipGuestImportCompleted")
                .isEqualTo("VipGuestImportCompleted");
        assertThat(outbox.getStatus()).as("Case1: status=PENDING").isEqualTo("PENDING");
        assertThat(outbox.getCreatedAt()).as("Case1: createdAt set").isNotNull();
        assertThat(outbox.getId()).as("Case1: outbox id generated").isNotNull();

        // Parse envelope payload
        JsonNode envelope = parsePayload(outbox.getPayload());
        assertThat(envelope.get("messageId").asText())
                .as("Case1: messageId non-null UUID")
                .isNotBlank();
        assertThat(envelope.get("eventVersion").asText())
                .as("Case1: eventVersion=1.0")
                .isEqualTo("1.0");
        assertThat(envelope.get("occurredAt").asText())
                .as("Case1: occurredAt non-null")
                .isNotBlank();

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("importJobId").asText())
                .as("Case1: payload.importJobId=jobId")
                .isEqualTo(jobId.toString());
        assertThat(payload.get("concertId").asText())
                .as("Case1: payload.concertId=CONCERT_ID")
                .isEqualTo(CONCERT_ID.toString());
        assertThat(payload.get("status").asText())
                .as("Case1: payload.status=COMPLETED")
                .isEqualTo("COMPLETED");
        assertThat(payload.get("totalRows").asInt())
                .as("Case1: payload.totalRows=3")
                .isEqualTo(3);
        assertThat(payload.get("successRows").asInt())
                .as("Case1: payload.successRows=3")
                .isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // Case 2: PARTIALLY_COMPLETED → outbox eventType=VipGuestImportCompleted, payload.status=PARTIALLY_COMPLETED
    // -----------------------------------------------------------------------

    @Test
    void case2_partiallyCompleted_outboxCompleted_payloadStatusPartially() throws Exception {
        ImportJobEntity job = seedProcessingJob();
        UUID jobId = job.getId();

        var event = completedEvent(jobId, "PARTIALLY_COMPLETED", 4, 3, 1, 0);
        boolean applied = persistence.markTerminal(jobId, "PARTIALLY_COMPLETED", 3, "error-reports/" + jobId + ".csv", event);

        assertThat(applied).as("Case2: applied").isTrue();

        List<OutboxEntity> rows = outboxRepository.findAll();
        assertThat(rows).as("Case2: 1 outbox row").hasSize(1);

        OutboxEntity outbox = rows.get(0);
        assertThat(outbox.getEventType())
                .as("Case2: eventType=VipGuestImportCompleted (not Failed)")
                .isEqualTo("VipGuestImportCompleted");

        JsonNode envelope = parsePayload(outbox.getPayload());
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("status").asText())
                .as("Case2: payload.status=PARTIALLY_COMPLETED")
                .isEqualTo("PARTIALLY_COMPLETED");
        assertThat(payload.get("totalRows").asInt()).as("Case2: totalRows=4").isEqualTo(4);
        assertThat(payload.get("successRows").asInt()).as("Case2: successRows=3").isEqualTo(3);
        assertThat(payload.get("failedRows").asInt()).as("Case2: failedRows=1").isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Case 3: FAILED (threshold) → outbox eventType=VipGuestImportFailed, failureReason=ERROR_THRESHOLD_EXCEEDED
    // -----------------------------------------------------------------------

    @Test
    void case3_failedThreshold_outboxFailed_failureReasonErrorThreshold() throws Exception {
        ImportJobEntity job = seedProcessingJob();
        UUID jobId = job.getId();

        var event = failedEvent(jobId, "ERROR_THRESHOLD_EXCEEDED");
        boolean applied = persistence.markTerminal(jobId, "FAILED", 0, "error-reports/" + jobId + ".csv", event);

        assertThat(applied).as("Case3: applied").isTrue();

        List<OutboxEntity> rows = outboxRepository.findAll();
        assertThat(rows).as("Case3: 1 outbox row").hasSize(1);

        OutboxEntity outbox = rows.get(0);
        assertThat(outbox.getEventType())
                .as("Case3: eventType=VipGuestImportFailed")
                .isEqualTo("VipGuestImportFailed");
        assertThat(outbox.getStatus()).as("Case3: status=PENDING").isEqualTo("PENDING");

        JsonNode envelope = parsePayload(outbox.getPayload());
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("failureReason").asText())
                .as("Case3: failureReason=ERROR_THRESHOLD_EXCEEDED")
                .isEqualTo("ERROR_THRESHOLD_EXCEEDED");
        assertThat(payload.get("importJobId").asText())
                .as("Case3: payload.importJobId=jobId")
                .isEqualTo(jobId.toString());
    }

    // -----------------------------------------------------------------------
    // Case 4: exception-FAILED (markFailed) → outbox eventType=VipGuestImportFailed, job status=FAILED
    // -----------------------------------------------------------------------

    @Test
    void case4_exceptionFailed_markFailed_outboxFailed_jobStatusFailed() throws Exception {
        ImportJobEntity job = seedProcessingJob();
        UUID jobId = job.getId();

        var event = failedEvent(jobId, "SERVICE_UNAVAILABLE");
        boolean applied = persistence.markFailed(jobId, "SERVICE_UNAVAILABLE", event);

        assertThat(applied).as("Case4: markFailed applied").isTrue();

        // job status must be FAILED
        ImportJobEntity updated = importJobRepository.findById(jobId).orElseThrow();
        assertThat(updated.getStatus()).as("Case4: job status=FAILED").isEqualTo("FAILED");
        assertThat(updated.getFailureReason())
                .as("Case4: failureReason=SERVICE_UNAVAILABLE")
                .isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(updated.getFinishedAt()).as("Case4: finishedAt set by markFailed").isNotNull();

        List<OutboxEntity> rows = outboxRepository.findAll();
        assertThat(rows).as("Case4: 1 outbox row").hasSize(1);

        OutboxEntity outbox = rows.get(0);
        assertThat(outbox.getEventType())
                .as("Case4: eventType=VipGuestImportFailed")
                .isEqualTo("VipGuestImportFailed");
        assertThat(outbox.getAggregateId()).as("Case4: aggregateId=jobId").isEqualTo(jobId);

        JsonNode envelope = parsePayload(outbox.getPayload());
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("failureReason").asText())
                .as("Case4: payload.failureReason=SERVICE_UNAVAILABLE")
                .isEqualTo("SERVICE_UNAVAILABLE");
    }

    // -----------------------------------------------------------------------
    // Case 5: atomic-no-double — markTerminal×2 → outbox count stays 1
    // -----------------------------------------------------------------------

    @Test
    void case5_atomicNoDouble_markTerminalTwice_outboxCountStaysOne() {
        ImportJobEntity job = seedProcessingJob();
        UUID jobId = job.getId();

        var event1 = completedEvent(jobId, "COMPLETED", 3, 3, 0, 0);
        boolean first = persistence.markTerminal(jobId, "COMPLETED", 3, null, event1);
        assertThat(first).as("Case5: first markTerminal applied").isTrue();
        assertThat(outboxRepository.count()).as("Case5: outbox count=1 after first call").isEqualTo(1);

        // Second call: job is now COMPLETED (not PROCESSING) → affected=0 → no outbox row
        var event2 = completedEvent(jobId, "COMPLETED", 3, 3, 0, 0);
        boolean second = persistence.markTerminal(jobId, "COMPLETED", 3, null, event2);
        assertThat(second).as("Case5: second markTerminal is no-op (affected=0)").isFalse();

        assertThat(outboxRepository.count())
                .as("Case5: outbox count still 1 (no double-event written)")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Case 6: no-PII — outbox payload JSON has no email/name/rawData/raw_data key
    // -----------------------------------------------------------------------

    @Test
    void case6_noPii_outboxPayload_noEmailNameRawData() throws Exception {
        ImportJobEntity job = seedProcessingJob();
        UUID jobId = job.getId();

        var event = completedEvent(jobId, "COMPLETED", 5, 5, 0, 0);
        persistence.markTerminal(jobId, "COMPLETED", 5, null, event);

        OutboxEntity outbox = outboxRepository.findAll().get(0);
        JsonNode envelope = parsePayload(outbox.getPayload());

        // Check the full envelope (top-level + nested payload)
        String payloadStr = outbox.getPayload();
        assertThat(payloadStr)
                .as("Case6: no 'email' key in payload JSON")
                .doesNotContain("\"email\"");
        assertThat(payloadStr)
                .as("Case6: no 'fullName' key in payload JSON")
                .doesNotContain("\"fullName\"")
                .doesNotContain("\"full_name\"");
        assertThat(payloadStr)
                .as("Case6: no 'rawData' key in payload JSON")
                .doesNotContain("\"rawData\"")
                .doesNotContain("\"raw_data\"");
        assertThat(payloadStr)
                .as("Case6: no 'name' (person name) key in payload JSON — only counts/IDs allowed")
                .doesNotContainPattern("\"name\"\\s*:\\s*\"[^\"]{2,}\"");
    }

    // -----------------------------------------------------------------------
    // Case 7: envelope §6.4 — payload JSON has exactly 5 top-level fields
    // -----------------------------------------------------------------------

    @Test
    void case7_envelope_exactlyFiveTopLevelFields_matchesSpec() throws Exception {
        ImportJobEntity job = seedProcessingJob();
        UUID jobId = job.getId();

        var event = completedEvent(jobId, "COMPLETED", 2, 2, 0, 0);
        persistence.markTerminal(jobId, "COMPLETED", 2, null, event);

        OutboxEntity outbox = outboxRepository.findAll().get(0);
        JsonNode envelope = parsePayload(outbox.getPayload());

        // Count top-level fields
        int fieldCount = 0;
        Iterator<String> fieldNames = envelope.fieldNames();
        while (fieldNames.hasNext()) {
            fieldNames.next();
            fieldCount++;
        }
        assertThat(fieldCount)
                .as("Case7: envelope must have exactly 5 top-level fields (§6.4): messageId,eventType,eventVersion,occurredAt,payload")
                .isEqualTo(5);

        // Verify each required field is present
        assertThat(envelope.has("messageId")).as("Case7: has messageId").isTrue();
        assertThat(envelope.has("eventType")).as("Case7: has eventType").isTrue();
        assertThat(envelope.has("eventVersion")).as("Case7: has eventVersion").isTrue();
        assertThat(envelope.has("occurredAt")).as("Case7: has occurredAt").isTrue();
        assertThat(envelope.has("payload")).as("Case7: has payload").isTrue();

        // Must NOT have disallowed fields (§6.4 explicitly excludes source/timestamp)
        assertThat(envelope.has("source")).as("Case7: no 'source' field (§6.4)").isFalse();
        assertThat(envelope.has("timestamp")).as("Case7: no 'timestamp' field (§6.4)").isFalse();
    }
}
