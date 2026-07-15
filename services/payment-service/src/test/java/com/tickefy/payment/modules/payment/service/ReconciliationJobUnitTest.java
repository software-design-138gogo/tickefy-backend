package com.tickefy.payment.modules.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.modules.payment.cache.PaymentIdempotencyCache;
import com.tickefy.payment.modules.payment.entity.OutboxEntity;
import com.tickefy.payment.modules.payment.entity.PaymentStatus;
import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import com.tickefy.payment.modules.payment.gateway.PaymentGatewayClient;
import com.tickefy.payment.modules.payment.gateway.PaymentGatewayException;
import com.tickefy.payment.modules.payment.gateway.SePayClient.QueryStatusResult;
import com.tickefy.payment.modules.payment.repository.OutboxRepository;
import com.tickefy.payment.modules.payment.repository.PaymentTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for ReconciliationJob (3.3) and related PaymentTxService resolve methods.
 * Pure Mockito, @Tag("unit"), NO @SpringBootTest / Docker.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ReconciliationJobUnitTest {

    // ---- ReconciliationJob deps ----
    @Mock
    private PaymentTransactionRepository txRepo;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private PaymentTxService paymentTxService;

    // ---- PaymentTxService deps (for resolveSuccess/resolveFailed tests) ----
    @Mock
    private OutboxRepository outboxRepo;

    @Mock
    private PaymentIdempotencyCache idempotencyCache;

    private PaymentStateMachine stateMachine; // real — pure logic, no Spring

    private ObjectMapper objectMapper; // real — Jackson

    // SUT instances
    private ReconciliationJob reconciliationJob;
    private PaymentTxService realTxService; // for resolve* tests

    @BeforeEach
    void setUp() {
        // ReconciliationJob with mocked deps (paymentTxService mocked)
        reconciliationJob = new ReconciliationJob(txRepo, paymentGatewayClient, paymentTxService);
        ReflectionTestUtils.setField(reconciliationJob, "staleAfter", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(reconciliationJob, "batchSize", 50);

        // Real PaymentTxService for resolve* tests
        stateMachine = new PaymentStateMachine();
        objectMapper = new ObjectMapper();
        realTxService = new PaymentTxService(
                txRepo, outboxRepo, idempotencyCache, stateMachine, objectMapper);
        ReflectionTestUtils.setField(realTxService, "provider", "MOCK_SEPAY");
        ReflectionTestUtils.setField(realTxService, "paymentExpiry", Duration.ofMinutes(15));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PaymentTransaction buildPendingTx(UUID id, String gatewayOrderId) {
        return PaymentTransaction.builder()
                .id(id)
                .orderId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .amount(150_000L)
                .currency("VND")
                .idempotencyKey("idem-" + id)
                .gatewayOrderId(gatewayOrderId)
                .status(PaymentStatus.PENDING.name())
                .createdAt(Instant.now().minusSeconds(900)) // 15 min ago → stale
                .updatedAt(Instant.now().minusSeconds(900))
                .build();
    }

    private PaymentTransaction buildTxWithStatus(UUID id, PaymentStatus status) {
        return PaymentTransaction.builder()
                .id(id)
                .orderId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .amount(150_000L)
                .currency("VND")
                .idempotencyKey("idem-" + id)
                .gatewayOrderId("MOCK-GW-" + id)
                .status(status.name())
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();
    }

    // ============================================================
    // AC-recon-success: queryStatus→"SUCCESS" → resolveSuccess called once, resolveFailed not called
    // ============================================================

    @Test
    void acReconSuccess_queryStatusSuccess_callsResolveSuccessOnce_neverResolveFailed() {
        UUID txId = UUID.randomUUID();
        String gatewayOrderId = "MOCK-GW-" + txId;
        String gatewayTxnId = "GW-TXN-OK-001";
        PaymentTransaction tx = buildPendingTx(txId, gatewayOrderId);

        when(txRepo.findByStatusAndCreatedAtBefore(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(tx));
        when(paymentGatewayClient.queryStatus(gatewayOrderId, tx.getAmount()))
                .thenReturn(new QueryStatusResult(gatewayTxnId, "SUCCESS"));

        reconciliationJob.reconcile();

        verify(paymentTxService, times(1)).resolveSuccess(txId, gatewayTxnId);
        verify(paymentTxService, never()).resolveFailed(any(), anyString());
    }

    // ============================================================
    // AC-recon-failed: queryStatus→"FAILED" → resolveFailed called, resolveSuccess not called
    // ============================================================

    @Test
    void acReconFailed_queryStatusFailed_callsResolveFailedOnce_neverResolveSuccess() {
        UUID txId = UUID.randomUUID();
        String gatewayOrderId = "MOCK-GW-FAIL-" + txId;
        PaymentTransaction tx = buildPendingTx(txId, gatewayOrderId);

        when(txRepo.findByStatusAndCreatedAtBefore(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(tx));
        when(paymentGatewayClient.queryStatus(gatewayOrderId, tx.getAmount()))
                .thenReturn(new QueryStatusResult("GW-TXN-FAIL-001", "FAILED"));

        reconciliationJob.reconcile();

        verify(paymentTxService, times(1)).resolveFailed(txId, "PAYMENT_FAILED");
        verify(paymentTxService, never()).resolveSuccess(any(), anyString());
    }

    // ============================================================
    // AC-recon-pending: queryStatus→"PENDING" → neither resolve called (skip/log)
    // ============================================================

    @Test
    void acReconPending_queryStatusPending_neitherResolveCalledSkipped() {
        UUID txId = UUID.randomUUID();
        String gatewayOrderId = "MOCK-GW-SKIP-" + txId;
        PaymentTransaction tx = buildPendingTx(txId, gatewayOrderId);

        when(txRepo.findByStatusAndCreatedAtBefore(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(tx));
        when(paymentGatewayClient.queryStatus(gatewayOrderId, tx.getAmount()))
                .thenReturn(new QueryStatusResult("GW-TXN-STILL-PEND", "PENDING"));

        reconciliationJob.reconcile();

        verify(paymentTxService, never()).resolveSuccess(any(), anyString());
        verify(paymentTxService, never()).resolveFailed(any(), anyString());
    }

    // ============================================================
    // AC-recon-cbopen: gateway throws PaymentGatewayException (CB open) for tx1,
    // tx2 is SUCCESS → tx2 still resolved; batch does NOT die/throw
    // ============================================================

    @Test
    void acReconCbOpen_firstTxThrowsGatewayException_secondTxStillResolvedBatchSurvives() {
        UUID tx1Id = UUID.randomUUID();
        UUID tx2Id = UUID.randomUUID();
        String gwId1 = "MOCK-GW-CBFAIL-" + tx1Id;
        String gwId2 = "MOCK-GW-CBOK-" + tx2Id;
        String gaTxnId2 = "GW-TXN-OK-" + tx2Id;

        PaymentTransaction tx1 = buildPendingTx(tx1Id, gwId1);
        PaymentTransaction tx2 = buildPendingTx(tx2Id, gwId2);

        when(txRepo.findByStatusAndCreatedAtBefore(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(tx1, tx2));

        // tx1 → CB open → throws PaymentGatewayException
        when(paymentGatewayClient.queryStatus(gwId1, tx1.getAmount()))
                .thenThrow(new PaymentGatewayException("CB open - circuit breaker is OPEN"));
        // tx2 → SUCCESS
        when(paymentGatewayClient.queryStatus(gwId2, tx2.getAmount()))
                .thenReturn(new QueryStatusResult(gaTxnId2, "SUCCESS"));

        // Should NOT throw (batch continues after exception)
        assertDoesNotThrow(() -> reconciliationJob.reconcile(),
                "AC-recon-cbopen: reconcile() must not throw even if one tx has gateway exception");

        // tx1: NO resolve called (skipped due to exception)
        verify(paymentTxService, never()).resolveSuccess(eq(tx1Id), anyString());
        verify(paymentTxService, never()).resolveFailed(eq(tx1Id), anyString());

        // tx2: resolveSuccess called (batch survived)
        verify(paymentTxService, times(1)).resolveSuccess(tx2Id, gaTxnId2);
    }

    // ============================================================
    // AC-recon-batch-empty: repo returns empty → nothing called
    // ============================================================

    @Test
    void acReconBatchEmpty_repoReturnsEmpty_nothingCalled() {
        when(txRepo.findByStatusAndCreatedAtBefore(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        reconciliationJob.reconcile();

        verify(paymentGatewayClient, never()).queryStatus(anyString(), any());
        verify(paymentTxService, never()).resolveSuccess(any(), anyString());
        verify(paymentTxService, never()).resolveFailed(any(), anyString());
    }

    // ============================================================
    // AC-recon-key: queryStatus called with tx.getGatewayOrderId() [§F.2]
    // ============================================================

    @Test
    void acReconKey_queryStatusCalledWithGatewayOrderId() {
        UUID txId = UUID.randomUUID();
        String expectedKey = "SPECIFIC-GW-ORDER-ID-XYZ";
        PaymentTransaction tx = buildPendingTx(txId, expectedKey);

        when(txRepo.findByStatusAndCreatedAtBefore(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(tx));
        when(paymentGatewayClient.queryStatus(expectedKey, tx.getAmount()))
                .thenReturn(new QueryStatusResult("GW-TXN", "PENDING"));

        reconciliationJob.reconcile();

        // Verify queryStatus called exactly with gatewayOrderId, NOT tx.getId()
        verify(paymentGatewayClient, times(1)).queryStatus(expectedKey, tx.getAmount());
        verify(paymentGatewayClient, never()).queryStatus(eq(txId.toString()), any());
    }

    // ============================================================
    // AC-resolve-success-ok: PENDING → SUCCESS + outbox PaymentSucceeded
    // payload: paymentTransactionId=tx.id, status=SUCCESS, has paidAt, no reason
    // sets gatewayTransactionId if null
    // ============================================================

    @Test
    void acResolveSuccessOk_pendingTxBecomesSuccess_outboxPaymentSucceededWithCorrectFields() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction tx = buildTxWithStatus(paymentId, PaymentStatus.PENDING);
        tx.setGatewayTransactionId(null); // null → should be set

        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String newGatewayTxnId = "NEW-GW-TXN-" + UUID.randomUUID();
        realTxService.resolveSuccess(paymentId, newGatewayTxnId);

        // Status must change to SUCCESS
        assertThat(tx.getStatus())
                .as("AC-resolve-success-ok: status must be SUCCESS")
                .isEqualTo(PaymentStatus.SUCCESS.name());

        // gatewayTransactionId should be set from parameter
        assertThat(tx.getGatewayTransactionId())
                .as("AC-resolve-success-ok: gatewayTransactionId must be set when was null")
                .isEqualTo(newGatewayTxnId);

        // Capture outbox
        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo, times(1)).save(outboxCaptor.capture());
        OutboxEntity saved = outboxCaptor.getValue();

        assertThat(saved.getEventType())
                .as("AC-resolve-success-ok: outbox eventType must be PaymentSucceeded")
                .isEqualTo("PaymentSucceeded");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getAggregateId()).isEqualTo(paymentId);

        // Parse payload
        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertThat(payload.get("paymentTransactionId").asText())
                .as("AC-resolve-success-ok: payload.paymentTransactionId must match tx.id")
                .isEqualTo(paymentId.toString());
        assertThat(payload.get("status").asText())
                .as("AC-resolve-success-ok: payload.status must be SUCCESS")
                .isEqualTo("SUCCESS");
        assertThat(payload.has("paidAt"))
                .as("AC-resolve-success-ok: SUCCESS payload must have paidAt")
                .isTrue();
        assertThat(payload.has("reason"))
                .as("AC-resolve-success-ok: SUCCESS payload must NOT have reason")
                .isFalse();
    }

    // ============================================================
    // AC-resolve-idempotent-guard: tx status=SUCCESS (terminal) → guard skip,
    // outboxRepo.save NEVER called, NO exception thrown
    // ============================================================

    @Test
    void acResolveIdempotentGuard_txAlreadySuccess_skipNoOutboxNoException() {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction tx = buildTxWithStatus(paymentId, PaymentStatus.SUCCESS);

        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));

        // Must not throw 422 or anything
        assertDoesNotThrow(() -> realTxService.resolveSuccess(paymentId, "GW-TXN-ALREADY-SUCCESS"),
                "AC-resolve-idempotent-guard: must not throw when status is already SUCCESS");

        // outboxRepo.save must NEVER be called (guard returned early)
        verify(outboxRepo, never()).save(any());
        // txRepo.save should not be called either (returned before save)
        verify(txRepo, never()).save(any());
    }

    // ============================================================
    // AC-resolve-dedup: txRepo.save throws DataIntegrityViolationException with uq_payment_gateway_txn
    // → no-op return, outbox NOT saved
    // ============================================================

    @Test
    void acResolveDedup_savethrowsUqGatewayTxnViolation_noOpNoOutbox() {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction tx = buildTxWithStatus(paymentId, PaymentStatus.PENDING);

        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenThrow(
                new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint \"uq_payment_gateway_txn\""));

        // Should NOT throw (dedup no-op)
        assertDoesNotThrow(() -> realTxService.resolveSuccess(paymentId, "GW-TXN-DUPE"),
                "AC-resolve-dedup: DataIntegrityViolationException on uq_payment_gateway_txn must be no-op");

        // outboxRepo.save must NOT be called
        verify(outboxRepo, never()).save(any());
    }

    // ============================================================
    // AC-resolve-failed-ok: PENDING → FAILED + outbox PaymentFailed reason=PAYMENT_FAILED
    // ============================================================

    @Test
    void acResolveFailedOk_pendingTxBecomesFailed_outboxPaymentFailedWithReason() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction tx = buildTxWithStatus(paymentId, PaymentStatus.PENDING);

        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        realTxService.resolveFailed(paymentId, "PAYMENT_FAILED");

        // Status must change to FAILED
        assertThat(tx.getStatus())
                .as("AC-resolve-failed-ok: status must be FAILED")
                .isEqualTo(PaymentStatus.FAILED.name());

        // Capture outbox
        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo, times(1)).save(outboxCaptor.capture());
        OutboxEntity saved = outboxCaptor.getValue();

        assertThat(saved.getEventType())
                .as("AC-resolve-failed-ok: outbox eventType must be PaymentFailed")
                .isEqualTo("PaymentFailed");
        assertThat(saved.getAggregateId()).isEqualTo(paymentId);

        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertThat(payload.get("reason").asText())
                .as("AC-resolve-failed-ok: payload.reason must be PAYMENT_FAILED")
                .isEqualTo("PAYMENT_FAILED");
        assertThat(payload.get("status").asText())
                .as("AC-resolve-failed-ok: payload.status must be FAILED")
                .isEqualTo("FAILED");
        assertThat(payload.has("paidAt"))
                .as("AC-resolve-failed-ok: FAILED payload must NOT have paidAt")
                .isFalse();
    }

    // ============================================================
    // AC-resolve-failed-guard: status!=PENDING (e.g. FAILED already) → skip, no save, no outbox
    // ============================================================

    @Test
    void acResolveFailedGuard_txAlreadyFailed_skipNoOutboxNoException() {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction tx = buildTxWithStatus(paymentId, PaymentStatus.FAILED);

        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));

        assertDoesNotThrow(() -> realTxService.resolveFailed(paymentId, "PAYMENT_FAILED"),
                "AC-resolve-failed-guard: must not throw when status is already FAILED");

        verify(outboxRepo, never()).save(any());
        verify(txRepo, never()).save(any());
    }

    // ============================================================
    // AC-B2-parity: buildInnerPayload(tx, SUCCESS, null) → same method used by resolveSuccess
    // and by handleCallback success path; assert payload fields for SUCCESS:
    // orderId, paymentTransactionId, status, amount, currency, provider, paidAt — NO reason.
    // ============================================================

    @Test
    void acB2Parity_buildInnerPayloadSuccess_hasAllRequiredFields_noReason() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentTransaction tx = PaymentTransaction.builder()
                .id(paymentId)
                .orderId(orderId)
                .userId(UUID.randomUUID())
                .amount(200_000L)
                .currency("VND")
                .idempotencyKey("idem-b2")
                .gatewayOrderId("MOCK-GW-B2")
                .gatewayTransactionId("GW-TXN-B2-001")
                .status(PaymentStatus.SUCCESS.name())
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();

        // Call buildInnerPayload directly (package-private but same package in test)
        String json = realTxService.buildInnerPayload(tx, PaymentStatus.SUCCESS, null);

        assertThat(json).isNotBlank();
        JsonNode node = objectMapper.readTree(json);

        // Required fields per B2 parity spec
        assertThat(node.get("orderId").asText())
                .as("AC-B2-parity: payload.orderId must match tx.orderId")
                .isEqualTo(orderId.toString());
        assertThat(node.get("paymentTransactionId").asText())
                .as("AC-B2-parity: payload.paymentTransactionId must match tx.id")
                .isEqualTo(paymentId.toString());
        assertThat(node.get("status").asText())
                .as("AC-B2-parity: payload.status must be SUCCESS")
                .isEqualTo("SUCCESS");
        assertThat(node.get("amount").asLong())
                .as("AC-B2-parity: payload.amount must match tx.amount")
                .isEqualTo(200_000L);
        assertThat(node.get("currency").asText())
                .as("AC-B2-parity: payload.currency must match tx.currency")
                .isEqualTo("VND");
        assertThat(node.get("provider").asText())
                .as("AC-B2-parity: payload.provider must be MOCK_SEPAY")
                .isEqualTo("MOCK_SEPAY");
        assertThat(node.has("paidAt"))
                .as("AC-B2-parity: SUCCESS payload must have paidAt")
                .isTrue();
        assertThat(node.get("paidAt").asText())
                .as("AC-B2-parity: paidAt must be non-blank ISO-8601")
                .isNotBlank();
        assertThat(node.has("reason"))
                .as("AC-B2-parity: SUCCESS payload must NOT have reason")
                .isFalse();
    }

    // ============================================================
    // AC-queryStatusFallback: PaymentGatewayClient.queryStatusFallback throws PaymentGatewayException
    // NOT ApiException
    // ============================================================

    @Test
    void acQueryStatusFallback_fallbackThrowsPaymentGatewayException_notApiException() throws Exception {
        // Invoke queryStatusFallback via reflection (package-private method)
        com.tickefy.payment.modules.payment.gateway.MockSePayClient mockDelegate =
                new com.tickefy.payment.modules.payment.gateway.MockSePayClient();
        PaymentGatewayClient client = new PaymentGatewayClient(mockDelegate);

        Throwable cause = new RuntimeException("upstream gateway timeout");

        // Verify that queryStatusFallback throws PaymentGatewayException
        var method = PaymentGatewayClient.class.getDeclaredMethod(
                "queryStatusFallback", String.class, Throwable.class);
        method.setAccessible(true);

        Exception thrown = null;
        try {
            method.invoke(client, "some-key", cause);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            thrown = (Exception) ite.getCause();
        }

        assertThat(thrown)
                .as("AC-queryStatusFallback: fallback must throw an exception")
                .isNotNull();
        assertThat(thrown)
                .as("AC-queryStatusFallback: exception must be PaymentGatewayException")
                .isInstanceOf(PaymentGatewayException.class);
        assertThat(thrown)
                .as("AC-queryStatusFallback: exception must NOT be ApiException")
                .isNotInstanceOf(ApiException.class);
    }

    // ============================================================
    // AC-regress: ensure existing PaymentTxService tests still hold —
    // verify resolveSuccess with gatewayTransactionId already set → NOT overwritten
    // ============================================================

    @Test
    void acRegress_resolveSuccess_existingGatewayTransactionId_notOverwritten() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction tx = buildTxWithStatus(paymentId, PaymentStatus.PENDING);
        String existingGwTxnId = "EXISTING-GW-TXN-ALREADY-SET";
        tx.setGatewayTransactionId(existingGwTxnId); // already set

        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        realTxService.resolveSuccess(paymentId, "SHOULD-NOT-OVERWRITE");

        // gatewayTransactionId must retain original value
        assertThat(tx.getGatewayTransactionId())
                .as("AC-regress: gatewayTransactionId must NOT be overwritten if already set")
                .isEqualTo(existingGwTxnId);
    }

    // ============================================================
    // AC-regress: resolveFailed with reason=PAYMENT_FAILED (from reconcile) sets correct reason
    // ============================================================

    @Test
    void acRegress_resolveFailed_reasonPaymentFailed_correctReasonInOutbox() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction tx = buildTxWithStatus(paymentId, PaymentStatus.PENDING);

        when(txRepo.findById(paymentId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        realTxService.resolveFailed(paymentId, "PAYMENT_FAILED");

        ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo, times(1)).save(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.get("reason").asText())
                .as("AC-regress: reconcile resolveFailed reason must be PAYMENT_FAILED [§F.1]")
                .isEqualTo("PAYMENT_FAILED");
    }

    // ============================================================
    // Extra coverage: reconcile passes correct cutoff to repo
    // (staleAfter=10min → cutoff = now minus 10min → createdAtBefore that cutoff)
    // ============================================================

    @Test
    void acReconCutoff_repoCalledWithCorrectCutoffInstant() {
        when(txRepo.findByStatusAndCreatedAtBefore(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        Instant before = Instant.now().minusSeconds(599); // just under 10 min
        reconciliationJob.reconcile();

        // Verify repo called with status="PENDING" and some cutoff (cannot be future)
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(txRepo, times(1)).findByStatusAndCreatedAtBefore(
                eq("PENDING"), cutoffCaptor.capture(), any(Pageable.class));

        Instant capturedCutoff = cutoffCaptor.getValue();
        assertThat(capturedCutoff)
                .as("AC-recon-cutoff: cutoff must be in the past (before now)")
                .isBefore(Instant.now());
        // Cutoff should be approximately now - staleAfter (10 min)
        assertThat(capturedCutoff)
                .as("AC-recon-cutoff: cutoff must be at least 9 minutes ago")
                .isBefore(Instant.now().minusSeconds(9 * 60));
    }
}
