package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tickefy.order.BaseIntegrationTest;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Consumer IT for ConcertCancelledConsumer — uses a REAL RabbitMQ Testcontainer so the listener
 * actually connects and consumes (listener-auto-startup forced true here only, §6.12). The base
 * class supplies the Postgres container + Flyway.
 *
 * <p>Covers: E1 happy (PAID→REFUND_PENDING), E2 idempotent (2× publish), E3 non-PAID untouched,
 * E4 other-concert untouched, E5 bad message → DLQ (never requeued).
 */
@Testcontainers
@TestPropertySource(properties = {"app.messaging.listener-auto-startup=true"})
class ConcertCancelledConsumerIntegrationTest extends BaseIntegrationTest {

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    private static final String EXCHANGE = "tickefy.exchange";
    private static final String ROUTING_KEY = "concert.cancelled";
    private static final String QUEUE = "order-service.concert-cancelled.queue";
    private static final String DLQ = "order-service.concert-cancelled.queue.dlq";

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM orders");
        try {
            rabbitAdmin.purgeQueue(QUEUE, false);
            rabbitAdmin.purgeQueue(DLQ, false);
        } catch (Exception ignored) {
            // queues may not exist yet on first run
        }
    }

    private UUID seedOrder(UUID concertId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orders (id, user_id, concert_id, status, idempotency_key, total_amount,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, UUID.randomUUID(), concertId, status, "ccit-" + id, 100000L);
        return id;
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private void publishConcertCancelled(String concertIdJsonValue) {
        String json = """
                {
                  "messageId": "%s",
                  "eventType": "ConcertCancelled",
                  "eventVersion": "1.0",
                  "source": "event-service",
                  "occurredAt": "%s",
                  "correlationId": "%s",
                  "payload": { "concertId": %s, "cancelledAt": "%s", "reason": "venue-issue" }
                }
                """
                .formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID(),
                        concertIdJsonValue, Instant.now());

        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message msg = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .andProperties(props)
                .build();
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, msg);
    }

    @Test
    void e1_paidOrders_movedToRefundPending() {
        UUID concert = UUID.randomUUID();
        UUID o1 = seedOrder(concert, "PAID");
        UUID o2 = seedOrder(concert, "PAID");

        publishConcertCancelled("\"" + concert + "\"");

        await().atMost(15, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(statusOf(o1)).isEqualTo("REFUND_PENDING");
                    assertThat(statusOf(o2)).isEqualTo("REFUND_PENDING");
                });
    }

    @Test
    void e2_idempotent_publishTwice_staysRefundPending() {
        UUID concert = UUID.randomUUID();
        UUID o1 = seedOrder(concert, "PAID");

        publishConcertCancelled("\"" + concert + "\"");
        await().atMost(15, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .until(() -> "REFUND_PENDING".equals(statusOf(o1)));

        // Second publish — bulk update WHERE status='PAID' matches 0 rows now → no-op, no error.
        publishConcertCancelled("\"" + concert + "\"");
        await().atMost(8, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(statusOf(o1)).isEqualTo("REFUND_PENDING"));
    }

    @Test
    void e3_nonPaidOrders_untouched() {
        UUID concert = UUID.randomUUID();
        UUID paid = seedOrder(concert, "PAID");
        UUID expired = seedOrder(concert, "EXPIRED");
        UUID failed = seedOrder(concert, "PAYMENT_FAILED");

        publishConcertCancelled("\"" + concert + "\"");

        await().atMost(15, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .until(() -> "REFUND_PENDING".equals(statusOf(paid)));
        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        assertThat(statusOf(failed)).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void e4_otherConcert_untouched() {
        UUID cancelled = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID inCancelled = seedOrder(cancelled, "PAID");
        UUID inOther = seedOrder(other, "PAID");

        publishConcertCancelled("\"" + cancelled + "\"");

        await().atMost(15, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .until(() -> "REFUND_PENDING".equals(statusOf(inCancelled)));
        assertThat(statusOf(inOther)).isEqualTo("PAID");
    }

    @Test
    void e5_badMessage_missingConcertId_routesToDlq_ordersUntouched() {
        UUID concert = UUID.randomUUID();
        UUID paid = seedOrder(concert, "PAID");

        publishConcertCancelled("null"); // payload.concertId = null → consumer throws → DLQ

        await().atMost(15, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var props = rabbitAdmin.getQueueProperties(DLQ);
                    assertThat(props).as("DLQ must exist").isNotNull();
                    Integer depth = (Integer) props.get("QUEUE_MESSAGE_COUNT");
                    assertThat(depth).as("bad message dead-lettered, not requeued").isGreaterThanOrEqualTo(1);
                });
        assertThat(statusOf(paid)).isEqualTo("PAID");
    }
}
