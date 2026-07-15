package com.tickefy.payment.modules.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.modules.payment.entity.OutboxEntity;
import com.tickefy.payment.modules.payment.repository.OutboxRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class OutboxPublisherUnitTest {

    @Mock
    private OutboxRepository outboxRepo;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private OutboxPublisher outboxPublisher;

    private static final String EXCHANGE = "tickefy.exchange";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxPublisher = new OutboxPublisher(outboxRepo, rabbitTemplate, objectMapper);
        ReflectionTestUtils.setField(outboxPublisher, "exchange", EXCHANGE);
    }

    // -----------------------------------------------------------------------
    // Helper: build an OutboxEntity with given eventType and innerPayload
    // -----------------------------------------------------------------------
    private OutboxEntity buildRow(String eventType, String innerPayload) {
        UUID id = UUID.randomUUID();
        return OutboxEntity.builder()
                .id(id)
                .aggregateId(UUID.randomUUID())
                .eventType(eventType)
                .payload(innerPayload)
                .status("PENDING")
                .createdAt(Instant.now().minusSeconds(5))
                .build();
    }

    // -----------------------------------------------------------------------
    // Bonus-A: drain builds correct envelope for PaymentSucceeded
    //   - messageId = row.id
    //   - eventType = "PaymentSucceeded"
    //   - eventVersion = "1.0"
    //   - timestamp present (non-null ISO-8601)
    //   - payload.orderId, payload.paymentTransactionId, payload.status = "SUCCESS"
    // -----------------------------------------------------------------------
    @Test
    void bonusA_drain_paymentSucceeded_buildsCorrectEnvelope() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentTxId = UUID.randomUUID();

        String innerPayload = objectMapper.createObjectNode()
                .put("orderId", orderId.toString())
                .put("paymentTransactionId", paymentTxId.toString())
                .put("status", "SUCCESS")
                .put("amount", 150000)
                .put("currency", "VND")
                .put("provider", "MOCK_SEPAY")
                .put("paidAt", Instant.now().toString())
                .toString();

        OutboxEntity row = buildRow("PaymentSucceeded", innerPayload);

        when(outboxRepo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.drain();

        // Capture the Message sent to rabbitTemplate
        ArgumentCaptor<Message> msgCaptor = forClass(Message.class);
        ArgumentCaptor<String> rkCaptor = forClass(String.class);
        verify(rabbitTemplate, times(1)).send(eq(EXCHANGE), rkCaptor.capture(), msgCaptor.capture());

        // routingKey must be payment.succeeded
        assertThat(rkCaptor.getValue())
                .as("Bonus-A: routing key for PaymentSucceeded must be payment.succeeded")
                .isEqualTo("payment.succeeded");

        // Parse envelope body
        Message msg = msgCaptor.getValue();
        JsonNode env = objectMapper.readTree(msg.getBody());

        assertThat(env.get("messageId").asText())
                .as("Bonus-A: envelope.messageId must equal row.id")
                .isEqualTo(row.getId().toString());
        assertThat(env.get("eventType").asText())
                .as("Bonus-A: envelope.eventType must be PaymentSucceeded")
                .isEqualTo("PaymentSucceeded");
        assertThat(env.get("eventVersion").asText())
                .as("Bonus-A: envelope.eventVersion must be 1.0")
                .isEqualTo("1.0");
        assertThat(env.has("timestamp"))
                .as("Bonus-A: envelope must have 'timestamp' field (not occurredAt — B2 contract)")
                .isTrue();
        assertThat(env.get("timestamp").asText())
                .as("Bonus-A: envelope.timestamp must be non-blank")
                .isNotBlank();

        JsonNode payload = env.get("payload");
        assertThat(payload.get("orderId").asText())
                .as("Bonus-A: envelope.payload.orderId must match")
                .isEqualTo(orderId.toString());
        assertThat(payload.get("paymentTransactionId").asText())
                .as("Bonus-A: envelope.payload.paymentTransactionId must match")
                .isEqualTo(paymentTxId.toString());
        assertThat(payload.get("status").asText())
                .as("Bonus-A: envelope.payload.status must be SUCCESS")
                .isEqualTo("SUCCESS");

        // messageProperties.messageId must be set
        assertThat(msg.getMessageProperties().getMessageId())
                .as("Bonus-A: message.messageProperties.messageId must be set to row.id")
                .isEqualTo(row.getId().toString());
    }

    // -----------------------------------------------------------------------
    // Bonus-B: drain builds correct routing key for PaymentFailed
    // -----------------------------------------------------------------------
    @Test
    void bonusB_drain_paymentFailed_routingKeyIsPaymentFailed() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentTxId = UUID.randomUUID();

        String innerPayload = objectMapper.createObjectNode()
                .put("orderId", orderId.toString())
                .put("paymentTransactionId", paymentTxId.toString())
                .put("status", "FAILED")
                .put("reason", "PAYMENT_FAILED")
                .toString();

        OutboxEntity row = buildRow("PaymentFailed", innerPayload);

        when(outboxRepo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.drain();

        ArgumentCaptor<String> rkCaptor = forClass(String.class);
        verify(rabbitTemplate, times(1)).send(eq(EXCHANGE), rkCaptor.capture(), any(Message.class));

        assertThat(rkCaptor.getValue())
                .as("Bonus-B: routing key for PaymentFailed must be payment.failed")
                .isEqualTo("payment.failed");
    }

    // -----------------------------------------------------------------------
    // Bonus-C: drain with unknown eventType marks row FAILED, does NOT publish
    // -----------------------------------------------------------------------
    @Test
    void bonusC_drain_unknownEventType_marksFailedNeverPublishes() {
        OutboxEntity row = buildRow("UnknownEvent", "{\"foo\":\"bar\"}");

        when(outboxRepo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxPublisher.drain();

        // Must NOT send to rabbitTemplate
        verify(rabbitTemplate, never()).send(any(), any(), any(Message.class));

        // Row status must be set to FAILED
        assertThat(row.getStatus())
                .as("Bonus-C: row.status must be FAILED for unknown eventType")
                .isEqualTo("FAILED");
    }

    // -----------------------------------------------------------------------
    // Bonus-D: drain with empty PENDING list does nothing
    // -----------------------------------------------------------------------
    @Test
    void bonusD_drain_noPendingRows_doesNothing() {
        when(outboxRepo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        outboxPublisher.drain();

        verify(rabbitTemplate, never()).send(any(), any(), any(Message.class));
        verify(outboxRepo, never()).save(any());
    }
}
