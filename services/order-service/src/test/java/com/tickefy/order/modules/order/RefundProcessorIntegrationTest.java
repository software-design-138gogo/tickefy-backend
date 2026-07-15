package com.tickefy.order.modules.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tickefy.order.BaseIntegrationTest;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.entity.OutboxEntity;
import com.tickefy.order.modules.order.entity.RefundJobEntity;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.repository.OutboxRepository;
import com.tickefy.order.modules.order.repository.RefundJobRepository;
import com.tickefy.order.modules.order.service.RefundProcessor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class RefundProcessorIntegrationTest extends BaseIntegrationTest {

    private static final String REFUND_PATH = "/internal/payments/refund";
    private static final WireMockServer PAYMENT = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort().http2PlainDisabled(true));

    @Autowired RefundProcessor refundProcessor;
    @Autowired OrderRepository orderRepository;
    @Autowired RefundJobRepository refundJobRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired ObjectMapper objectMapper;

    @BeforeAll
    static void startPayment() {
        PAYMENT.start();
    }

    @AfterAll
    static void stopPayment() {
        PAYMENT.stop();
    }

    @DynamicPropertySource
    static void paymentProperties(DynamicPropertyRegistry registry) {
        registry.add("app.payment.stub", () -> "false");
        registry.add("app.payment.base-url", PAYMENT::baseUrl);
        registry.add("app.payment.refund-path", () -> REFUND_PATH);
        registry.add("app.refund.worker.enabled", () -> "false");
    }

    @BeforeEach
    void cleanDatabaseAndStubs() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
        refundJobRepository.deleteAll();
        PAYMENT.resetAll();
    }

    @Test
    void enabledJob_twoPendingOrders_successAnd503_produceOneRefundAndOneOutbox_withoutReplayDuplicate()
            throws Exception {
        UUID concertId = UUID.randomUUID();
        UUID successfulOrderId = UUID.randomUUID();
        UUID retryOrderId = UUID.randomUUID();
        UUID paymentTransactionId = UUID.randomUUID();
        long successfulAmount = 150_000L;
        long retryAmount = 220_000L;

        refundJobRepository.save(RefundJobEntity.builder()
                .concertId(concertId)
                .enabledAt(Instant.now())
                .status("ENABLED")
                .build());
        orderRepository.save(pendingOrder(successfulOrderId, concertId, successfulAmount, Instant.now().minusSeconds(2)));
        orderRepository.save(pendingOrder(retryOrderId, concertId, retryAmount, Instant.now().minusSeconds(1)));

        PAYMENT.stubFor(post(urlEqualTo(REFUND_PATH))
                .withRequestBody(equalToJson(requestJson(successfulOrderId, successfulAmount)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successJson(paymentTransactionId))));
        PAYMENT.stubFor(post(urlEqualTo(REFUND_PATH))
                .withRequestBody(equalToJson(requestJson(retryOrderId, retryAmount)))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":{\"code\":\"PAYMENT_GATEWAY_UNAVAILABLE\",\"message\":\"retry\"}}")));

        refundProcessor.processRefunds();

        assertThat(orderRepository.findById(successfulOrderId).orElseThrow().getStatus()).isEqualTo("REFUNDED");
        assertThat(orderRepository.findById(retryOrderId).orElseThrow().getStatus()).isEqualTo("REFUND_PENDING");
        assertThat(outboxRepository.findAll()).singleElement().satisfies(row -> assertRefundedEnvelope(
                row, successfulOrderId, concertId, successfulAmount, paymentTransactionId));

        refundProcessor.processRefunds();

        assertThat(outboxRepository.findAll()).hasSize(1);
        PAYMENT.verify(1, postRequestedFor(urlEqualTo(REFUND_PATH))
                .withRequestBody(equalToJson(requestJson(successfulOrderId, successfulAmount))));
        PAYMENT.verify(2, postRequestedFor(urlEqualTo(REFUND_PATH))
                .withRequestBody(equalToJson(requestJson(retryOrderId, retryAmount))));
    }

    private void assertRefundedEnvelope(
            OutboxEntity row, UUID orderId, UUID concertId, long amount, UUID paymentTransactionId) {
        assertThat(row.getAggregateId()).isEqualTo(orderId);
        assertThat(row.getEventType()).isEqualTo("OrderRefunded");
        assertThat(row.getStatus()).isEqualTo("PENDING");
        try {
            JsonNode envelope = objectMapper.readTree(row.getPayload());
            assertThat(envelope.path("eventType").asText()).isEqualTo("OrderRefunded");
            assertThat(envelope.path("eventVersion").asText()).isEqualTo("1.0");
            assertThat(envelope.path("occurredAt").asText()).isNotBlank();
            JsonNode payload = envelope.path("payload");
            assertThat(payload.path("orderId").asText()).isEqualTo(orderId.toString());
            assertThat(payload.path("concertId").asText()).isEqualTo(concertId.toString());
            assertThat(payload.path("refundAmount").asLong()).isEqualTo(amount);
            assertThat(payload.path("paymentTransactionId").asText()).isEqualTo(paymentTransactionId.toString());
            assertThat(payload.path("refundedAt").asText()).isEqualTo(envelope.path("occurredAt").asText());
        } catch (Exception exception) {
            throw new AssertionError("OrderRefunded outbox payload must be valid JSON", exception);
        }
    }

    private OrderEntity pendingOrder(UUID id, UUID concertId, long amount, Instant createdAt) {
        return OrderEntity.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .concertId(concertId)
                .status("REFUND_PENDING")
                .idempotencyKey("refund-it-" + id)
                .paymentTransactionId(UUID.randomUUID().toString())
                .totalAmount(amount)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private String requestJson(UUID orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"refundRequestId\":\"refund-" + orderId + "\",\"amount\":" + amount + "}";
    }

    private String successJson(UUID paymentTransactionId) {
        return "{\"success\":true,\"data\":{\"status\":\"REFUNDED\",\"refundGatewayRef\":\"GW-IT-1\",\"paymentTransactionId\":\""
                + paymentTransactionId + "\"},\"error\":null}";
    }
}
