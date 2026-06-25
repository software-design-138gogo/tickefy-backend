package com.tickefy.checkin.modules.vip.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.modules.vip.repository.ProcessedMessageRepository;
import com.tickefy.checkin.modules.vip.repository.VipCacheMetaRepository;
import com.tickefy.checkin.support.PostgresContainerITBase;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * VipImportConsumerIT — failsafe (real-db-test profile), §TASK vip-2.
 *
 * <p>Extends PostgresContainerITBase (real Postgres, Flyway on, schema checkin_service).
 * Adds its own RabbitMQ Testcontainer so listeners start for real.
 *
 * <p>AC map:
 * <ul>
 *   <li>AC-VIC-1 receive→STALE+processed: valid envelope → vip_cache_meta.state=STALE,
 *       processed_messages has messageId.</li>
 *   <li>AC-VIC-2 dedup 2×: same messageId published twice → exactly 1 row in processed_messages,
 *       no error.</li>
 *   <li>AC-VIC-3 DLQ bad-msg: malformed message (missing concertId) → ends up in DLQ, NEVER
 *       requeued (requeue-rejected=false).</li>
 * </ul>
 *
 * <p>Note: repull integration (STALE→ensureFresh→csvVipClient) is already covered deterministically
 * in VipProjectionServiceIT (AC-VPS-3 stale→repull). The consumer's responsibility is only
 * mark-stale + dedup; the repull path is NOT in the consumer code path. Splitting is correct.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(
        properties = {
            // Override listener-auto-startup=true so listeners actually connect to broker
            "app.messaging.listener-auto-startup=true"
        })
class VipImportConsumerIT extends PostgresContainerITBase {

    // -------------------------------------------------------------------------
    // RabbitMQ Testcontainer
    // -------------------------------------------------------------------------

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        // Point checkin-service at the Testcontainer broker
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // Keep rabbit health OFF so actuator doesn't gate context startup
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    // -------------------------------------------------------------------------
    // Test constants (UUID literals — no random, deterministic per-test with @BeforeEach clean)
    // -------------------------------------------------------------------------

    private static final UUID CONCERT_ID = UUID.fromString("d1d1d1d1-d1d1-d1d1-d1d1-d1d1d1d1d1d1");
    private static final UUID MSG_ID_1 = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    private static final UUID MSG_ID_2 = UUID.fromString("b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2");
    private static final UUID JOB_ID = UUID.fromString("c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3");

    private static final String EXCHANGE = "tickefy.exchange";
    private static final String ROUTING_KEY = "vip-guest-import.completed";
    private static final String QUEUE = "checkin.vip-guest-import-completed";
    private static final String DLQ = "checkin.vip-guest-import-completed.dlq";

    // -------------------------------------------------------------------------
    // Spring beans
    // -------------------------------------------------------------------------

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private VipCacheMetaRepository metaRepo;

    @Autowired
    private ProcessedMessageRepository processedMessageRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Setup: clean DB state between tests; queues self-declared by RabbitMqConfig @Bean on startup
    // -------------------------------------------------------------------------

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update(
                "DELETE FROM checkin_service.vip_cache_meta WHERE concert_id = ?", CONCERT_ID);
        jdbcTemplate.update(
                "DELETE FROM checkin_service.processed_messages WHERE message_id IN (?, ?)",
                MSG_ID_1, MSG_ID_2);

        // Drain both queues so stale messages from prior tests don't interfere
        drainQueue(QUEUE);
        drainQueue(DLQ);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a valid envelope JSON and publish it as a raw AMQP message
     * to tickefy.exchange with routing key vip-guest-import.completed.
     * We send as raw bytes (content-type application/json) to match the Jackson converter shape
     * that the real csv-ingestion OutboxPublisher emits.
     */
    private void publishValidEnvelope(UUID messageId, UUID concertId, UUID importJobId)
            throws Exception {
        String json = """
                {
                  "messageId": "%s",
                  "eventType": "VipGuestImportCompleted",
                  "eventVersion": "1.0",
                  "occurredAt": "%s",
                  "payload": {
                    "importJobId": "%s",
                    "concertId": "%s",
                    "status": "COMPLETED",
                    "totalRows": 10,
                    "successRows": 10,
                    "failedRows": 0,
                    "duplicateRows": 0
                  }
                }
                """
                .formatted(messageId, Instant.now(), importJobId, concertId);

        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(messageId.toString());
        Message msg = MessageBuilder.withBody(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .andProperties(props)
                .build();

        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, msg);
    }

    /** Drain a queue to empty (consume all pending messages, discard). */
    private void drainQueue(String queueName) {
        // Purge via RabbitAdmin if queue exists; fallback: receive loop
        try {
            rabbitAdmin.purgeQueue(queueName, false);
        } catch (Exception ignored) {
            // queue may not exist yet on first test run — safe to ignore
        }
    }

    // -------------------------------------------------------------------------
    // AC-VIC-1: receive valid envelope → vip_cache_meta STALE + processed_messages record
    // -------------------------------------------------------------------------

    @Test
    void ac1_receiveValidEnvelope_marksStaleAndRecordsProcessed() throws Exception {
        publishValidEnvelope(MSG_ID_1, CONCERT_ID, JOB_ID);

        // Awaitility: wait up to 10s for consumer to process
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(processedMessageRepo.existsById(MSG_ID_1))
                            .as("AC-VIC-1: processed_messages must contain messageId=" + MSG_ID_1)
                            .isTrue();

                    var meta = metaRepo.findById(CONCERT_ID);
                    assertThat(meta)
                            .as("AC-VIC-1: vip_cache_meta must have a row for concertId=" + CONCERT_ID)
                            .isPresent();
                    assertThat(meta.get().getState())
                            .as("AC-VIC-1: vip_cache_meta.state must be STALE")
                            .isEqualTo("STALE");
                });
    }

    // -------------------------------------------------------------------------
    // AC-VIC-2: dedup — publish same messageId twice → exactly 1 row, no error
    // -------------------------------------------------------------------------

    @Test
    void ac2_dedup_sameMessageIdTwice_exactlyOneRow_noError() throws Exception {
        // First publish
        publishValidEnvelope(MSG_ID_1, CONCERT_ID, JOB_ID);

        // Wait for first to be processed
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> processedMessageRepo.existsById(MSG_ID_1));

        // Second publish with SAME messageId
        publishValidEnvelope(MSG_ID_1, CONCERT_ID, JOB_ID);

        // Wait a beat then assert: still exactly 1 row (consumer skips dup via existsById check)
        // Use a small extra wait to ensure second message is consumed (acked without re-inserting)
        await().atMost(8, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM checkin_service.processed_messages WHERE message_id = ?",
                            Long.class,
                            MSG_ID_1);
                    assertThat(count)
                            .as("AC-VIC-2: processed_messages must have exactly 1 row for messageId="
                                    + MSG_ID_1 + " (no duplicate insert on dedup)")
                            .isEqualTo(1L);
                });

        // State still STALE (idempotent — no crash from second message)
        var meta = metaRepo.findById(CONCERT_ID);
        assertThat(meta).as("AC-VIC-2: vip_cache_meta still exists").isPresent();
        assertThat(meta.get().getState())
                .as("AC-VIC-2: state must still be STALE after dedup")
                .isEqualTo("STALE");
    }

    // -------------------------------------------------------------------------
    // AC-VIC-3: bad message (invalid concertId field: null payload) → DLQ, not requeued
    //
    // Strategy: send JSON with payload.concertId = null (which triggers NPE / IllegalArgumentException
    // in VipImportConsumer because env.payload().concertId() == null → throw).
    // The factory has setDefaultRequeueRejected(false) so exception → DLQ immediately.
    // We assert DLQ has >= 1 message after a short wait.
    // -------------------------------------------------------------------------

    @Test
    void ac3_badMessage_missingConcertId_routesToDlq() throws Exception {
        // Envelope with null concertId — will fail the null check in VipImportConsumer
        String badJson =
                """
                {
                  "messageId": "%s",
                  "eventType": "VipGuestImportCompleted",
                  "eventVersion": "1.0",
                  "occurredAt": "%s",
                  "payload": {
                    "importJobId": "%s",
                    "concertId": null,
                    "status": "COMPLETED",
                    "totalRows": 1,
                    "successRows": 1,
                    "failedRows": 0,
                    "duplicateRows": 0
                  }
                }
                """
                        .formatted(MSG_ID_2, Instant.now(), JOB_ID);

        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message msg = MessageBuilder.withBody(
                        badJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .andProperties(props)
                .build();

        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, msg);

        // Wait for message to appear in DLQ (requeue=false, exception → DLQ)
        await().atMost(12, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Check DLQ depth using RabbitAdmin queue properties
                    var props2 = rabbitAdmin.getQueueProperties(DLQ);
                    assertThat(props2)
                            .as("AC-VIC-3: DLQ '" + DLQ + "' must exist")
                            .isNotNull();
                    Integer depth = (Integer) props2.get("QUEUE_MESSAGE_COUNT");
                    assertThat(depth)
                            .as("AC-VIC-3: DLQ must have >= 1 message (bad msg dead-lettered, not requeued)")
                            .isGreaterThanOrEqualTo(1);
                });

        // Verify the message was NOT processed (no processed_messages row, no meta row)
        assertThat(processedMessageRepo.existsById(MSG_ID_2))
                .as("AC-VIC-3: bad message must NOT be recorded in processed_messages")
                .isFalse();
    }
}
