package com.tickefy.payment.modules.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
import com.tickefy.payment.modules.payment.cache.PaymentIdempotencyCache;
import com.tickefy.payment.modules.payment.entity.OutboxEntity;
import com.tickefy.payment.modules.payment.entity.PaymentStatus;
import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import com.tickefy.payment.modules.payment.repository.OutboxRepository;
import com.tickefy.payment.modules.payment.repository.PaymentTransactionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for PaymentTxService:
 *  - tx3SetFailedGatewayError: sets FAILED + saves outbox PaymentFailed reason=GATEWAY_ERROR
 *  - buildInnerPayload: correct fields for SUCCESS (paidAt, no reason) and FAILED (reason, no paidAt)
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PaymentTxServiceUnitTest {

    @Mock
    private PaymentTransactionRepository txRepo;

    @Mock
    private OutboxRepository outboxRepo;

    @Mock
    private PaymentIdempotencyCache idempotencyCache;

    @Mock
    private PaymentStateMachine stateMachine;

    private ObjectMapper objectMapper;
    private PaymentTxService paymentTxService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        paymentTxService = new PaymentTxService(
                txRepo, outboxRepo, idempotencyCache, stateMachine, objectMapper);
        ReflectionTestUtils.setField(paymentTxService, "provider", "MOCK_SEPAY");
        ReflectionTestUtils.setField(paymentTxService, "paymentExpiry", java.time.Duration.ofMinutes(15));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private PaymentTransaction buildTx(UUID id, UUID orderId, PaymentStatus status) {
        return PaymentTransaction.builder()
                .id(id)
                .orderId(orderId)
                .userId(UUID.randomUUID())
                .amount(150_000L)
                .currency("VND")
                .idempotencyKey("idem-" + id)
                .status(status.name())
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();
    }

    // ============================================================
    // AC-CB-fail-publish: tx3SetFailedGatewayError sets FAILED + outbox GATEWAY_ERROR
    // ============================================================

    /**
     * AC-CB-fail-pub-1: tx3SetFailedGatewayError sets tx status to FAILED
     * and saves OutboxEntity with eventType=PaymentFailed and payload containing reason=GATEWAY_ERROR.
     */
    @Test
    void acCbFailPub1_tx3SetFailed_setsStatusFailed_savesOutboxWithGatewayError() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        PaymentTransaction tx = buildTx(paymentId, orderId, PaymentStatus.INITIATED);
        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // stateMachine.assertTransition is void — default mock does nothing (INITIATED->FAILED is valid)

        paymentTxService.tx3SetFailedGatewayError(paymentId);

        // Verify tx status set to FAILED
        assertThat(tx.getStatus())
                .as("AC-CB-fail-pub-1: tx status must be FAILED after tx3")
                .isEqualTo(PaymentStatus.FAILED.name());

        // Verify outboxRepo.save called once
        verify(outboxRepo, times(1)).save(any(OutboxEntity.class));

        // Capture and inspect outbox entity
        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo).save(outboxCaptor.capture());
        OutboxEntity saved = outboxCaptor.getValue();

        assertThat(saved.getEventType())
                .as("AC-CB-fail-pub-1: outbox eventType must be PaymentFailed")
                .isEqualTo("PaymentFailed");
        assertThat(saved.getStatus())
                .as("AC-CB-fail-pub-1: outbox status must be PENDING")
                .isEqualTo("PENDING");
        assertThat(saved.getAggregateId())
                .as("AC-CB-fail-pub-1: outbox aggregateId must be paymentId")
                .isEqualTo(paymentId);

        // Parse payload and verify reason=GATEWAY_ERROR
        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertThat(payload.get("reason").asText())
                .as("AC-CB-fail-pub-1: payload.reason must be GATEWAY_ERROR")
                .isEqualTo("GATEWAY_ERROR");
        assertThat(payload.has("paidAt"))
                .as("AC-CB-fail-pub-1: FAILED payload must NOT have paidAt")
                .isFalse();
        assertThat(payload.get("status").asText())
                .as("AC-CB-fail-pub-1: payload.status must be FAILED")
                .isEqualTo("FAILED");
    }

    /**
     * AC-CB-fail-pub-2: tx3SetFailedGatewayError when payment not found -> throws INTERNAL_SERVER_ERROR.
     */
    @Test
    void acCbFailPub2_tx3SetFailed_txNotFound_throwsInternalServerError() {
        UUID paymentId = UUID.randomUUID();
        when(txRepo.findById(paymentId)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentTxService.tx3SetFailedGatewayError(paymentId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ============================================================
    // buildInnerPayload: SUCCESS -> paidAt present, no reason
    // ============================================================

    /**
     * AC-buildPayload-1: buildInnerPayload(tx, SUCCESS, null)
     * -> has paidAt (non-null, parseable as ISO-8601), has status=SUCCESS,
     *    has orderId, paymentTransactionId, amount, currency, provider.
     *    Does NOT have "reason" field.
     */
    @Test
    void acBuildPayload1_success_hasPaidAt_noReason() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction tx = buildTx(paymentId, orderId, PaymentStatus.SUCCESS);

        String json = paymentTxService.buildInnerPayload(tx, PaymentStatus.SUCCESS, null);

        assertThat(json).isNotBlank();
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("status").asText())
                .as("AC-buildPayload-1: status must be SUCCESS")
                .isEqualTo("SUCCESS");
        assertThat(node.has("paidAt"))
                .as("AC-buildPayload-1: SUCCESS payload must have paidAt")
                .isTrue();
        assertThat(node.get("paidAt").asText())
                .as("AC-buildPayload-1: paidAt must be non-blank ISO-8601")
                .isNotBlank();
        assertThat(node.has("reason"))
                .as("AC-buildPayload-1: SUCCESS payload must NOT have reason")
                .isFalse();
        assertThat(node.get("orderId").asText())
                .as("AC-buildPayload-1: must have orderId")
                .isEqualTo(orderId.toString());
        assertThat(node.get("paymentTransactionId").asText())
                .as("AC-buildPayload-1: must have paymentTransactionId")
                .isEqualTo(paymentId.toString());
        assertThat(node.get("amount").asLong())
                .as("AC-buildPayload-1: amount must be 150000")
                .isEqualTo(150_000L);
        assertThat(node.get("currency").asText())
                .as("AC-buildPayload-1: currency must be VND")
                .isEqualTo("VND");
        assertThat(node.get("provider").asText())
                .as("AC-buildPayload-1: provider must be MOCK_SEPAY")
                .isEqualTo("MOCK_SEPAY");
    }

    /**
     * AC-buildPayload-2: buildInnerPayload(tx, FAILED, "GATEWAY_ERROR")
     * -> has reason=GATEWAY_ERROR, has status=FAILED, does NOT have paidAt.
     */
    @Test
    void acBuildPayload2_failedGatewayError_hasReason_noPaidAt() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction tx = buildTx(paymentId, orderId, PaymentStatus.FAILED);

        String json = paymentTxService.buildInnerPayload(tx, PaymentStatus.FAILED, "GATEWAY_ERROR");

        assertThat(json).isNotBlank();
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("status").asText())
                .as("AC-buildPayload-2: status must be FAILED")
                .isEqualTo("FAILED");
        assertThat(node.get("reason").asText())
                .as("AC-buildPayload-2: reason must be GATEWAY_ERROR")
                .isEqualTo("GATEWAY_ERROR");
        assertThat(node.has("paidAt"))
                .as("AC-buildPayload-2: FAILED payload must NOT have paidAt")
                .isFalse();
    }

    /**
     * AC-buildPayload-3: buildInnerPayload(tx, FAILED, "PAYMENT_FAILED")
     * -> has reason=PAYMENT_FAILED, status=FAILED, no paidAt.
     */
    @Test
    void acBuildPayload3_failedPaymentFailed_hasReason_noPaidAt() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction tx = buildTx(paymentId, orderId, PaymentStatus.FAILED);

        String json = paymentTxService.buildInnerPayload(tx, PaymentStatus.FAILED, "PAYMENT_FAILED");

        assertThat(json).isNotBlank();
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("status").asText()).isEqualTo("FAILED");
        assertThat(node.get("reason").asText())
                .as("AC-buildPayload-3: reason must be PAYMENT_FAILED")
                .isEqualTo("PAYMENT_FAILED");
        assertThat(node.has("paidAt"))
                .as("AC-buildPayload-3: FAILED(PAYMENT_FAILED) payload must NOT have paidAt")
                .isFalse();
    }

    /**
     * AC-buildPayload-4: shape is consistent — SUCCESS and FAILED have same field set
     * except paidAt vs reason (mutually exclusive).
     */
    @Test
    void acBuildPayload4_successAndFailed_mutuallyExclusiveFields() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction tx = buildTx(paymentId, orderId, PaymentStatus.SUCCESS);

        String successJson = paymentTxService.buildInnerPayload(tx, PaymentStatus.SUCCESS, null);
        String failedJson = paymentTxService.buildInnerPayload(tx, PaymentStatus.FAILED, "GATEWAY_ERROR");

        JsonNode successNode = objectMapper.readTree(successJson);
        JsonNode failedNode = objectMapper.readTree(failedJson);

        // SUCCESS has paidAt, FAILED does not
        assertThat(successNode.has("paidAt")).isTrue();
        assertThat(failedNode.has("paidAt")).isFalse();

        // FAILED has reason, SUCCESS does not
        assertThat(failedNode.has("reason")).isTrue();
        assertThat(successNode.has("reason")).isFalse();

        // Both have the same base fields
        for (String field : new String[]{"orderId", "paymentTransactionId", "status", "amount", "currency", "provider"}) {
            assertThat(successNode.has(field))
                    .as("AC-buildPayload-4: SUCCESS must have field=" + field).isTrue();
            assertThat(failedNode.has(field))
                    .as("AC-buildPayload-4: FAILED must have field=" + field).isTrue();
        }
    }
}
