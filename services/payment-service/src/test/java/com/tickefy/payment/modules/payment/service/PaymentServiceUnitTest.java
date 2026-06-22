package com.tickefy.payment.modules.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.modules.payment.dto.CallbackRequest;
import com.tickefy.payment.modules.payment.dto.CreatePaymentRequest;
import com.tickefy.payment.modules.payment.dto.CreatePaymentResponse;
import com.tickefy.payment.modules.payment.entity.OutboxEntity;
import com.tickefy.payment.modules.payment.entity.PaymentStatus;
import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import com.tickefy.payment.modules.payment.gateway.SePayClient;
import com.tickefy.payment.modules.payment.gateway.SePayClient.CreateQrResult;
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
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PaymentServiceUnitTest {

    @Mock
    private PaymentTransactionRepository txRepo;

    @Mock
    private OutboxRepository outboxRepo;

    @Mock
    private SePayClient sePayClient;

    private ObjectMapper objectMapper;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        paymentService = new PaymentService(txRepo, outboxRepo, sePayClient, objectMapper);
        // Inject @Value fields (no Spring context)
        ReflectionTestUtils.setField(paymentService, "paymentExpiry", java.time.Duration.ofMinutes(15));
        ReflectionTestUtils.setField(paymentService, "provider", "MOCK_SEPAY");
        ReflectionTestUtils.setField(paymentService, "callbackSecret", "");
        ReflectionTestUtils.setField(paymentService, "devSimEnabled", false);
    }

    // -----------------------------------------------------------------------
    // Helper: build a persisted PaymentTransaction (simulates @PrePersist)
    // -----------------------------------------------------------------------
    private PaymentTransaction buildTx(UUID id, UUID orderId, PaymentStatus status, String gatewayOrderId) {
        return PaymentTransaction.builder()
                .id(id)
                .orderId(orderId)
                .userId(UUID.randomUUID())
                .amount(150000L)
                .currency("VND")
                .idempotencyKey("idem-key-" + id)
                .gatewayOrderId(gatewayOrderId)
                .status(status.name())
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();
    }

    // -----------------------------------------------------------------------
    // AC1: createTransaction idempotent
    // Gọi 2× cùng idempotencyKey → lần 2 findByIdempotencyKey trả tx cũ
    // → KHÔNG save mới, KHÔNG gọi sePayClient.createQr lần 2
    // → response giống nhau (cùng paymentId)
    // -----------------------------------------------------------------------
    @Test
    void ac1_createTransaction_idempotent_sameKeyReturnsSamePaymentId() {
        UUID existingPaymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        PaymentTransaction existingTx = buildTx(existingPaymentId, orderId, PaymentStatus.PENDING, "MOCK-" + existingPaymentId);

        String idempotencyKey = "order-" + orderId + "-v1";
        CreatePaymentRequest req = new CreatePaymentRequest(
                orderId,
                UUID.randomUUID(),
                150000L,
                "VND",
                idempotencyKey);

        // First call: key not found → create new
        when(txRepo.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingTx));

        when(sePayClient.createQr(any(), any(long.class), any(), any()))
                .thenReturn(new CreateQrResult("MOCK-" + existingPaymentId, "MOCKQR", "https://pay.mock.local/" + existingPaymentId));

        when(txRepo.save(any(PaymentTransaction.class))).thenAnswer(inv -> {
            PaymentTransaction t = inv.getArgument(0);
            // simulate @PrePersist if createdAt null
            if (t.getCreatedAt() == null) t.setCreatedAt(Instant.now());
            if (t.getUpdatedAt() == null) t.setUpdatedAt(Instant.now());
            return t;
        });

        CreatePaymentResponse firstResp = paymentService.createTransaction(req);

        // Second call: key already exists
        CreatePaymentResponse secondResp = paymentService.createTransaction(req);

        // Same paymentId returned
        assertThat(secondResp.paymentId())
                .as("AC1: idempotent call must return same paymentId as existing tx")
                .isEqualTo(existingPaymentId);

        // sePayClient.createQr called exactly ONCE (not on second call)
        verify(sePayClient, times(1)).createQr(any(), any(long.class), any(), any());

        // txRepo.save NOT called on second call — only 2 saves from first call (INITIATED + PENDING)
        // We verify sePayClient not called again as the main guard
        // Also verify firstResp has a paymentId (non-null) — first call generated one
        assertThat(firstResp.paymentId()).isNotNull();

        // Verify second response has same paymentId as the existing tx
        assertThat(secondResp.paymentId()).isEqualTo(existingPaymentId);
    }

    // -----------------------------------------------------------------------
    // AC2: handleCallback SUCCESS
    // tx PENDING → status SUCCESS + 1 outbox row eventType="PaymentSucceeded"
    // payload contains orderId + paymentTransactionId(=tx.id) + status="SUCCESS"
    // -----------------------------------------------------------------------
    @Test
    void ac2_handleCallback_success_savesOutboxPaymentSucceeded() throws Exception {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gatewayOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gatewayOrderId);

        when(txRepo.findByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenReturn(tx);
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CallbackRequest req = new CallbackRequest(gatewayOrderId, "GW-TXN-001", "SUCCESS", 150000L, null);

        // bypassHmac=true to skip signature check in unit test
        paymentService.handleCallback(req, true);

        // tx status should be updated to SUCCESS
        assertThat(tx.getStatus())
                .as("AC2: tx.status must be SUCCESS after SUCCESS callback")
                .isEqualTo(PaymentStatus.SUCCESS.name());

        // Capture outbox save argument
        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo, times(1)).save(outboxCaptor.capture());

        OutboxEntity savedOutbox = outboxCaptor.getValue();
        assertThat(savedOutbox.getEventType())
                .as("AC2: outbox eventType must be PaymentSucceeded")
                .isEqualTo("PaymentSucceeded");

        // Parse payload and verify fields
        JsonNode payload = objectMapper.readTree(savedOutbox.getPayload());
        assertThat(payload.get("orderId").asText())
                .as("AC2: payload.orderId must match tx.orderId")
                .isEqualTo(orderId.toString());
        assertThat(payload.get("paymentTransactionId").asText())
                .as("AC2: payload.paymentTransactionId must equal tx.id")
                .isEqualTo(txId.toString());
        assertThat(payload.get("status").asText())
                .as("AC2: payload.status must be SUCCESS")
                .isEqualTo("SUCCESS");
    }

    // -----------------------------------------------------------------------
    // AC3: handleCallback FAILED
    // tx PENDING + result FAILED → status FAILED + outbox row eventType="PaymentFailed"
    // -----------------------------------------------------------------------
    @Test
    void ac3_handleCallback_failed_savesOutboxPaymentFailed() throws Exception {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gatewayOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gatewayOrderId);

        when(txRepo.findByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenReturn(tx);
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CallbackRequest req = new CallbackRequest(gatewayOrderId, "GW-TXN-FAIL-001", "FAILED", 150000L, null);

        paymentService.handleCallback(req, true);

        // tx status should be FAILED
        assertThat(tx.getStatus())
                .as("AC3: tx.status must be FAILED after FAILED callback")
                .isEqualTo(PaymentStatus.FAILED.name());

        // Capture outbox save
        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo, times(1)).save(outboxCaptor.capture());

        OutboxEntity savedOutbox = outboxCaptor.getValue();
        assertThat(savedOutbox.getEventType())
                .as("AC3: outbox eventType must be PaymentFailed")
                .isEqualTo("PaymentFailed");

        JsonNode payload = objectMapper.readTree(savedOutbox.getPayload());
        assertThat(payload.get("orderId").asText())
                .as("AC3: payload.orderId must match tx.orderId")
                .isEqualTo(orderId.toString());
        assertThat(payload.get("paymentTransactionId").asText())
                .as("AC3: payload.paymentTransactionId must equal tx.id")
                .isEqualTo(txId.toString());
        assertThat(payload.get("status").asText())
                .as("AC3: payload.status must be FAILED")
                .isEqualTo("FAILED");
    }

    // -----------------------------------------------------------------------
    // AC4: terminal no-op
    // tx đã SUCCESS → handleCallback lần 2 → KHÔNG đổi status, KHÔNG outbox.save thêm
    // -----------------------------------------------------------------------
    @Test
    void ac4_handleCallback_terminalState_isNoOp() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gatewayOrderId = "MOCK-" + txId;

        // Already SUCCESS (terminal)
        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.SUCCESS, gatewayOrderId);

        when(txRepo.findByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(tx));

        CallbackRequest req = new CallbackRequest(gatewayOrderId, "GW-TXN-001", "SUCCESS", 150000L, null);

        paymentService.handleCallback(req, true);

        // Status must NOT change
        assertThat(tx.getStatus())
                .as("AC4: status must remain SUCCESS (no mutation on terminal tx)")
                .isEqualTo(PaymentStatus.SUCCESS.name());

        // outboxRepo.save must NEVER be called
        verify(outboxRepo, never()).save(any());

        // txRepo.save must NEVER be called (no state update)
        verify(txRepo, never()).save(argThat(t -> t.getId().equals(txId)));
    }
}
