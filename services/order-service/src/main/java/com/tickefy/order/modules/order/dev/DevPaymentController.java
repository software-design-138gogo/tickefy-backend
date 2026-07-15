package com.tickefy.order.modules.order.dev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.order.common.response.ApiResponse;
import com.tickefy.order.modules.order.messaging.OrderEvents;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV-ONLY stub for Payment. Publishes a REAL {@code payment.succeeded}/{@code payment.failed} event
 * (correct envelope + routing key) to {@code tickefy.exchange}, so the SAME consume path that Dương's
 * real Payment will use is exercised. When real Payment lands, just disable this (no order change).
 *
 * <p>Gated by {@code app.dev.payment-sim.enabled=true} — absent in prod image → bean not created.
 */
@RestController
@RequestMapping("/dev/orders")
@ConditionalOnProperty(name = "app.dev.payment-sim.enabled", havingValue = "true")
public class DevPaymentController {

    private static final Logger log = LoggerFactory.getLogger(DevPaymentController.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.messaging.exchange:tickefy.exchange}")
    private String exchange;

    public DevPaymentController(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        log.warn("DevPaymentController ACTIVE — payment simulation enabled (dev only)");
    }

    @PostMapping("/{orderId}/simulate-paid")
    public ResponseEntity<ApiResponse<Map<String, String>>> simulatePaid(@PathVariable UUID orderId) {
        String txId = "dev-tx-" + UUID.randomUUID();
        publish("payment.succeeded", "PaymentSucceeded", orderId, txId, "SUCCESS");
        return ResponseEntity.accepted()
                .body(ApiResponse.success(Map.of("orderId", orderId.toString(), "paymentTransactionId", txId), null));
    }

    @PostMapping("/{orderId}/simulate-failed")
    public ResponseEntity<ApiResponse<Map<String, String>>> simulateFailed(@PathVariable UUID orderId) {
        String txId = "dev-tx-" + UUID.randomUUID();
        publish("payment.failed", "PaymentFailed", orderId, txId, "FAILED");
        return ResponseEntity.accepted()
                .body(ApiResponse.success(Map.of("orderId", orderId.toString(), "paymentTransactionId", txId), null));
    }

    private void publish(String routingKey, String eventType, UUID orderId, String txId, String status) {
        OrderEvents.PaymentEnvelope envelope = new OrderEvents.PaymentEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                OrderEvents.EVENT_VERSION,
                Instant.now().toString(),
                new OrderEvents.PaymentPayload(orderId.toString(), txId, status));
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize dev payment event", e);
        }
        Message message = MessageBuilder
                .withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(envelope.messageId())
                .build();
        rabbitTemplate.send(exchange, routingKey, message);
        log.info("DEV published {} orderId={} txId={}", routingKey, orderId, txId);
    }
}
