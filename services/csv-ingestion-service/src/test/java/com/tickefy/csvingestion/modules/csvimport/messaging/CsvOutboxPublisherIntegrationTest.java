package com.tickefy.csvingestion.modules.csvimport.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.csvingestion.modules.csvimport.entity.OutboxEntity;
import com.tickefy.csvingestion.modules.csvimport.event.CsvEvents;
import com.tickefy.csvingestion.modules.csvimport.event.EventEnvelope;
import com.tickefy.csvingestion.modules.csvimport.event.VipGuestImportCompletedPayload;
import com.tickefy.csvingestion.modules.csvimport.event.VipGuestImportFailedPayload;
import com.tickefy.csvingestion.modules.csvimport.repository.OutboxRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-5b: CsvOutboxPublisherIntegrationTest.
 *
 * <p>Verifies the OutboxPublisher drain() method against a real RabbitMQ Testcontainer.
 * Calls drain() DIRECTLY (deterministic — not waiting for @Scheduled tick).
 *
 * <p>AC map:
 * <ul>
 *   <li>AC1: publish Completed — PENDING row → drain() → message on queue, rk=vip-guest-import.completed,
 *       content-type=application/json, messageId=outbox.id, body=payload; row PUBLISHED.</li>
 *   <li>AC2: publish Failed — eventType=VipGuestImportFailed → rk=vip-guest-import.failed.</li>
 *   <li>AC3: rk-derive — routing keys match EVENT CONTRACT constants in CsvEvents.RoutingKey.</li>
 *   <li>AC4: no-double — already-PUBLISHED row → drain() again → no new message on queue.</li>
 *   <li>AC5: unknown eventType — drain() → row status=FAILED, no message published.</li>
 *   <li>AC6: messageId preserved — message.getMessageId() equals outbox row id (dedup §6.9).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = "app.messaging.outbox.enabled=true")
class CsvOutboxPublisherIntegrationTest {

    // -----------------------------------------------------------------------
    // Testcontainers
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_outbox_publisher_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

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

        // RabbitMQ
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);

        // MinIO — not used, dummy creds satisfy context boot
        registry.add("app.object-storage.endpoint", () -> "http://localhost:19900");
        registry.add("app.object-storage.access-key", () -> "minioadmin");
        registry.add("app.object-storage.secret-key", () -> "minioadmin");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");

        // External services — not called
        registry.add("app.inventory.base-url", () -> "http://localhost:19901");
        registry.add("app.event.base-url", () -> "http://localhost:19902");

        // JWT — SecurityConfig loads but no HTTP requests in this test
        registry.add("app.jwt.public-key", () -> "classpath:keys/jwt-dev-public.pem");
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");

        // Worker auto-trigger OFF
        registry.add("app.csv.worker.auto-trigger", () -> "false");
        registry.add("app.csv.batch-size", () -> "500");
        registry.add("app.csv.error-threshold", () -> "0.5");
    }

    // -----------------------------------------------------------------------
    // Test constants
    // -----------------------------------------------------------------------

    static final String TEST_QUEUE = "csv-publisher-test.queue";
    static final UUID CONCERT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    // Receive timeout for rabbitTemplate.receive(): generous for CI but not infinite
    static final long RECEIVE_TIMEOUT_MS = 8_000L;

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Value("${app.messaging.exchange:tickefy.exchange}")
    String exchange;

    // -----------------------------------------------------------------------
    // Setup — declare test consumer queue + clean outbox between tests
    // -----------------------------------------------------------------------

    @BeforeEach
    void setup() {
        // Clean outbox between tests
        outboxRepository.deleteAll();

        // Declare test consumer queue and bind to tickefy.exchange with wildcard rk
        // so it receives both vip-guest-import.completed and vip-guest-import.failed.
        // non-durable, non-exclusive, non-auto-delete: persists across tests so purge works.
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        Queue q = new Queue(TEST_QUEUE, false, false, false);
        admin.declareQueue(q);
        // Bind with wildcard pattern to catch both routing keys
        admin.declareBinding(
                new org.springframework.amqp.core.Binding(
                        TEST_QUEUE,
                        org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                        exchange,
                        "vip-guest-import.#",
                        null));

        // Drain any residual messages from previous test (defensive purge) — AFTER declaration
        Message stale;
        do {
            stale = rabbitTemplate.receive(TEST_QUEUE, 500);
        } while (stale != null);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build a valid envelope JSON string for a Completed event. */
    private String buildCompletedPayload(UUID jobId) {
        try {
            EventEnvelope<VipGuestImportCompletedPayload> envelope = EventEnvelope.of(
                    CsvEvents.EventType.VIP_GUEST_IMPORT_COMPLETED,
                    new VipGuestImportCompletedPayload(jobId, CONCERT_ID, "COMPLETED", 3, 3, 0, 0));
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Completed payload", e);
        }
    }

    /** Build a valid envelope JSON string for a Failed event. */
    private String buildFailedPayload(UUID jobId) {
        try {
            EventEnvelope<VipGuestImportFailedPayload> envelope = EventEnvelope.of(
                    CsvEvents.EventType.VIP_GUEST_IMPORT_FAILED,
                    new VipGuestImportFailedPayload(jobId, CONCERT_ID, "ERROR_THRESHOLD_EXCEEDED"));
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Failed payload", e);
        }
    }

    /** Seed an outbox row in PENDING status. */
    private OutboxEntity seedPending(UUID jobId, String eventType, String payloadJson) {
        OutboxEntity row = OutboxEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(jobId)
                .eventType(eventType)
                .payload(payloadJson)
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
        return outboxRepository.save(row);
    }

    /** Count messages in the test queue without consuming them (receive + re-queue not possible;
     * we consume them into a list and count — fine for test assertions). */
    private int drainTestQueue() {
        int count = 0;
        while (rabbitTemplate.receive(TEST_QUEUE, 200) != null) {
            count++;
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // AC1: publish Completed
    // -----------------------------------------------------------------------

    @Test
    void ac1_publishCompleted_pendingRow_messageOnQueue_rowPublished() throws Exception {
        UUID jobId = UUID.randomUUID();
        String payloadJson = buildCompletedPayload(jobId);
        OutboxEntity row = seedPending(jobId, CsvEvents.EventType.VIP_GUEST_IMPORT_COMPLETED, payloadJson);

        // Call drain() directly — deterministic, no scheduler tick wait
        publisher.drain();

        // Receive message from test queue
        Message msg = rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS);

        assertThat(msg)
                .as("AC1: message must be received from queue after drain()")
                .isNotNull();

        // Routing key assertion via receivedRoutingKey
        String receivedRk = msg.getMessageProperties().getReceivedRoutingKey();
        assertThat(receivedRk)
                .as("AC1: routing key must be vip-guest-import.completed")
                .isEqualTo(CsvEvents.RoutingKey.COMPLETED);

        // Content-type
        assertThat(msg.getMessageProperties().getContentType())
                .as("AC1: content-type must be application/json")
                .isEqualTo("application/json");

        // Body JSON must contain the same data as the original payload (field order may differ due
        // to Postgres jsonb normalisation — compare as JSON trees, not as string-equal)
        String bodyStr = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(bodyStr))
                .as("AC1: message body JSON must equal outbox payload JSON (content-equal, order-agnostic)")
                .isEqualTo(objectMapper.readTree(payloadJson));

        // messageId in AMQP properties == outbox row id
        assertThat(msg.getMessageProperties().getMessageId())
                .as("AC1: AMQP messageId must equal outbox row id")
                .isEqualTo(row.getId().toString());

        // Row status must be PUBLISHED with publishedAt set
        OutboxEntity updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("AC1: outbox row status must be PUBLISHED after drain()")
                .isEqualTo("PUBLISHED");
        assertThat(updated.getPublishedAt())
                .as("AC1: publishedAt must be non-null after successful publish")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // AC2: publish Failed → rk = vip-guest-import.failed
    // -----------------------------------------------------------------------

    @Test
    void ac2_publishFailed_pendingRow_messageOnQueue_rkFailed() throws Exception {
        UUID jobId = UUID.randomUUID();
        String payloadJson = buildFailedPayload(jobId);
        OutboxEntity row = seedPending(jobId, CsvEvents.EventType.VIP_GUEST_IMPORT_FAILED, payloadJson);

        publisher.drain();

        Message msg = rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS);

        assertThat(msg)
                .as("AC2: message must arrive on test queue for Failed event")
                .isNotNull();

        String receivedRk = msg.getMessageProperties().getReceivedRoutingKey();
        assertThat(receivedRk)
                .as("AC2: routing key must be vip-guest-import.failed")
                .isEqualTo(CsvEvents.RoutingKey.FAILED);

        // Body JSON must match outbox payload content (order-agnostic — jsonb reorders keys)
        String bodyStr = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(bodyStr))
                .as("AC2: body JSON must equal outbox payload JSON (content-equal, order-agnostic)")
                .isEqualTo(objectMapper.readTree(payloadJson));

        // Row published
        OutboxEntity updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("AC2: row status=PUBLISHED")
                .isEqualTo("PUBLISHED");
    }

    // -----------------------------------------------------------------------
    // AC3: rk-derive matches EVENT CONTRACT constants
    // -----------------------------------------------------------------------

    @Test
    void ac3_rkDerive_completedAndFailed_matchContractConstants() throws Exception {
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();
        seedPending(jobId1, CsvEvents.EventType.VIP_GUEST_IMPORT_COMPLETED, buildCompletedPayload(jobId1));
        seedPending(jobId2, CsvEvents.EventType.VIP_GUEST_IMPORT_FAILED, buildFailedPayload(jobId2));

        publisher.drain();

        // Receive both messages — order may vary; collect routing keys
        Message m1 = rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS);
        Message m2 = rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS);

        assertThat(m1).as("AC3: first message received").isNotNull();
        assertThat(m2).as("AC3: second message received").isNotNull();

        var receivedRks = java.util.Set.of(
                m1.getMessageProperties().getReceivedRoutingKey(),
                m2.getMessageProperties().getReceivedRoutingKey());

        assertThat(receivedRks)
                .as("AC3: must contain completed rk=" + CsvEvents.RoutingKey.COMPLETED)
                .contains(CsvEvents.RoutingKey.COMPLETED);
        assertThat(receivedRks)
                .as("AC3: must contain failed rk=" + CsvEvents.RoutingKey.FAILED)
                .contains(CsvEvents.RoutingKey.FAILED);
    }

    // -----------------------------------------------------------------------
    // AC4: no-double — already-PUBLISHED row → second drain() → no new message
    // -----------------------------------------------------------------------

    @Test
    void ac4_noDouble_alreadyPublishedRow_drainAgain_noNewMessage() throws Exception {
        UUID jobId = UUID.randomUUID();
        String payloadJson = buildCompletedPayload(jobId);
        OutboxEntity row = seedPending(jobId, CsvEvents.EventType.VIP_GUEST_IMPORT_COMPLETED, payloadJson);

        // First drain — publishes message + marks PUBLISHED
        publisher.drain();

        // Consume the first message so queue is empty
        Message first = rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(first).as("AC4: setup — first message consumed").isNotNull();

        // Verify row is PUBLISHED
        Optional<OutboxEntity> opt = outboxRepository.findById(row.getId());
        assertThat(opt).isPresent();
        assertThat(opt.get().getStatus()).as("AC4: setup — row is PUBLISHED").isEqualTo("PUBLISHED");

        // Second drain — PUBLISHED row is NOT in findByStatusOrderByCreatedAtAsc("PENDING", ...) results
        publisher.drain();

        // No new message on queue
        Message second = rabbitTemplate.receive(TEST_QUEUE, 2_000L);
        assertThat(second)
                .as("AC4: no new message on queue after draining PUBLISHED row again")
                .isNull();

        // Row count stable — still 1 outbox row
        assertThat(outboxRepository.count())
                .as("AC4: outbox count still 1 — no duplicate rows created")
                .isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // AC5: unknown eventType → row status=FAILED, no message published
    // -----------------------------------------------------------------------

    @Test
    void ac5_unknownEventType_rowStatusFailed_noMessagePublished() throws Exception {
        UUID jobId = UUID.randomUUID();
        String payloadJson = "{\"messageId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"GarbageType\","
                + "\"eventVersion\":\"1.0\",\"occurredAt\":\"" + Instant.now() + "\","
                + "\"payload\":{\"importJobId\":\"" + jobId + "\"}}";
        OutboxEntity row = seedPending(jobId, "GarbageType", payloadJson);

        publisher.drain();

        // No message on queue — unknown eventType is never published
        Message msg = rabbitTemplate.receive(TEST_QUEUE, 2_000L);
        assertThat(msg)
                .as("AC5: no message must arrive for unknown eventType")
                .isNull();

        // Row status must be FAILED (not PENDING, not PUBLISHED)
        OutboxEntity updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("AC5: row status must be FAILED for unknown eventType")
                .isEqualTo("FAILED");
        assertThat(updated.getPublishedAt())
                .as("AC5: publishedAt must remain null (never published)")
                .isNull();
    }

    // -----------------------------------------------------------------------
    // AC6: messageId preserved — AMQP message.getMessageId() == outbox row id
    // -----------------------------------------------------------------------

    @Test
    void ac6_messageIdPreserved_amqpMessageIdEqualsOutboxRowId() throws Exception {
        UUID jobId = UUID.randomUUID();
        String payloadJson = buildCompletedPayload(jobId);
        OutboxEntity row = seedPending(jobId, CsvEvents.EventType.VIP_GUEST_IMPORT_COMPLETED, payloadJson);

        publisher.drain();

        Message msg = rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(msg).as("AC6: message must be received").isNotNull();

        String amqpMessageId = msg.getMessageProperties().getMessageId();
        assertThat(amqpMessageId)
                .as("AC6: AMQP messageId must be the outbox row UUID (dedup §6.9) — "
                        + "expected=" + row.getId() + ", got=" + amqpMessageId)
                .isEqualTo(row.getId().toString());
    }
}
