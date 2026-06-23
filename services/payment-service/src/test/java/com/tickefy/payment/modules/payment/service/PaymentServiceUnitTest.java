package com.tickefy.payment.modules.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
import com.tickefy.payment.modules.payment.cache.PaymentIdempotencyCache;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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

    @Mock
    private PaymentIdempotencyCache idempotencyCache;

    private ObjectMapper objectMapper;
    private PaymentService paymentService;

    private static final String TEST_SECRET = "test-secret-for-hmac";
    private static final Duration EXPIRY = Duration.ofMinutes(15);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        paymentService = new PaymentService(txRepo, outboxRepo, sePayClient, objectMapper, idempotencyCache);
        ReflectionTestUtils.setField(paymentService, "paymentExpiry", EXPIRY);
        ReflectionTestUtils.setField(paymentService, "provider", "MOCK_SEPAY");
        ReflectionTestUtils.setField(paymentService, "callbackSecret", "");
        ReflectionTestUtils.setField(paymentService, "requireSignature", false);
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private PaymentTransaction buildTx(UUID id, UUID orderId, PaymentStatus status, String gatewayOrderId) {
        return PaymentTransaction.builder()
                .id(id)
                .orderId(orderId)
                .userId(UUID.randomUUID())
                .amount(150_000L)
                .currency("VND")
                .idempotencyKey("idem-" + id)
                .gatewayOrderId(gatewayOrderId)
                .status(status.name())
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();
    }

    private CreatePaymentRequest buildCreateReq(UUID orderId, String idempotencyKey) {
        return new CreatePaymentRequest(orderId, UUID.randomUUID(), 150_000L, "VND", idempotencyKey);
    }

    /** Compute HMAC-SHA256 hex of canonical "gwOrderId|gwTxn|status|amount". */
    private String computeHmac(String secret, String gwOrderId, String gwTxn, String status, Long amount) throws Exception {
        String message = (gwOrderId != null ? gwOrderId : "")
                + "|" + (gwTxn != null ? gwTxn : "")
                + "|" + (status != null ? status : "")
                + "|" + (amount != null ? amount : "");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }

    // ============================================================
    // AC1 — LAYER-A CACHE FAST-PATH
    // ============================================================

    /** AC1a: cache.get returns paymentId + txRepo.findById present → return cached response, NEVER call sePayClient. */
    @Test
    void ac1a_cacheHit_txPresent_returnsCachedResponse_neverCallsGateway() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String idemKey = "key-ac1a";

        PaymentTransaction existingTx = buildTx(paymentId, orderId, PaymentStatus.PENDING, "MOCK-" + paymentId);

        when(idempotencyCache.get(idemKey)).thenReturn(Optional.of(paymentId));
        when(txRepo.findById(paymentId)).thenReturn(Optional.of(existingTx));

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId())
                .as("AC1a: cache hit must return existing paymentId")
                .isEqualTo(paymentId);

        verify(sePayClient, never()).createQr(any(), any(long.class), any(), any());
        verify(txRepo, never()).findByIdempotencyKey(any());
    }

    /** AC1b: cache miss + DB hit → populate cache (verify idempotencyCache.put), return existing paymentId, NEVER call sePayClient. */
    @Test
    void ac1b_cacheMiss_dbHit_populatesCache_neverCallsGateway() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String idemKey = "key-ac1b";

        PaymentTransaction existingTx = buildTx(paymentId, orderId, PaymentStatus.PENDING, "MOCK-" + paymentId);

        when(idempotencyCache.get(idemKey)).thenReturn(Optional.empty());
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.of(existingTx));

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId())
                .as("AC1b: DB hit must return existing paymentId")
                .isEqualTo(paymentId);

        verify(idempotencyCache, times(1)).put(idemKey, paymentId);
        verify(sePayClient, never()).createQr(any(), any(long.class), any(), any());
    }

    /** AC1c: both cache and DB miss → create new tx (verify txRepo.save + sePayClient.createQr called once). */
    @Test
    void ac1c_bothMiss_createsNewTx_callsGatewayOnce() {
        UUID orderId = UUID.randomUUID();
        String idemKey = "key-ac1c";

        when(idempotencyCache.get(idemKey)).thenReturn(Optional.empty());
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());
        when(txRepo.save(any(PaymentTransaction.class))).thenAnswer(inv -> {
            PaymentTransaction t = inv.getArgument(0);
            if (t.getCreatedAt() == null) t.setCreatedAt(Instant.now());
            if (t.getUpdatedAt() == null) t.setUpdatedAt(Instant.now());
            return t;
        });
        when(sePayClient.createQr(any(), any(long.class), any(), any()))
                .thenReturn(new CreateQrResult("MOCK-GW-001", "QR-PAYLOAD", "https://pay.mock.local/x"));

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId()).as("AC1c: new tx must have a non-null paymentId").isNotNull();
        assertThat(resp.paymentUrl()).isEqualTo("https://pay.mock.local/x");

        verify(sePayClient, times(1)).createQr(any(), any(long.class), any(), any());
        // save called twice: once INITIATED, once PENDING
        verify(txRepo, times(2)).save(any(PaymentTransaction.class));
    }

    // ============================================================
    // AC2 — LAYER-B DEDUP (state-guard + unique constraint)
    // ============================================================

    /** AC2a: callback on already-SUCCESS tx → no-op, NEVER call outboxRepo.save. */
    @Test
    void ac2a_callbackOnTerminalSuccess_isNoOp_neverSavesOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.SUCCESS, gwOrderId);

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-001", "SUCCESS", 150_000L, null);

        paymentService.handleCallback(req, true);

        verify(outboxRepo, never()).save(any());
        verify(txRepo, never()).save(any());
    }

    /** AC2b: callback on already-FAILED tx → no-op, NEVER call outboxRepo.save. */
    @Test
    void ac2b_callbackOnTerminalFailed_isNoOp_neverSavesOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.FAILED, gwOrderId);

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-FAIL", "FAILED", 150_000L, null);

        paymentService.handleCallback(req, true);

        verify(outboxRepo, never()).save(any());
        verify(txRepo, never()).save(any());
    }

    /** AC2c: txRepo.save throws DataIntegrityViolationException with "uq_payment_gateway_txn" → no-op, NEVER outboxRepo.save. */
    @Test
    void ac2c_duplicateGatewayTxnUniqueViolation_isNoOp_neverSavesOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenThrow(
                new DataIntegrityViolationException("ERROR: duplicate key value violates unique constraint \"uq_payment_gateway_txn\""));

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-DUPE", "SUCCESS", 150_000L, null);

        // Must NOT throw, must be silent no-op
        paymentService.handleCallback(req, true);

        verify(outboxRepo, never()).save(any());
    }

    /** AC2d: DataIntegrityViolationException with DIFFERENT message (not uq_payment_gateway_txn) → rethrow. */
    @Test
    void ac2d_otherIntegrityViolation_rethrows() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenThrow(
                new DataIntegrityViolationException("ERROR: duplicate key on some_other_constraint"));

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-OTHER", "SUCCESS", 150_000L, null);

        assertThrows(DataIntegrityViolationException.class,
                () -> paymentService.handleCallback(req, true),
                "AC2d: non-gateway-txn integrity violation must rethrow");
    }

    // ============================================================
    // AC8 — REDIS DOWN (fail-safe in PaymentIdempotencyCache)
    // ============================================================

    /** AC8-service: mock idempotencyCache always returns Optional.empty() (simulates Redis down) → service falls through to DB path, creates new tx. */
    @Test
    void ac8_redisDown_serviceFallsThroughToDb() {
        UUID orderId = UUID.randomUUID();
        String idemKey = "key-ac8";

        // Simulate Redis down: cache.get returns empty, cache.put is a no-op (already default mock)
        when(idempotencyCache.get(idemKey)).thenReturn(Optional.empty());
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());
        when(txRepo.save(any(PaymentTransaction.class))).thenAnswer(inv -> {
            PaymentTransaction t = inv.getArgument(0);
            if (t.getCreatedAt() == null) t.setCreatedAt(Instant.now());
            if (t.getUpdatedAt() == null) t.setUpdatedAt(Instant.now());
            return t;
        });
        when(sePayClient.createQr(any(), any(long.class), any(), any()))
                .thenReturn(new CreateQrResult("MOCK-GW-AC8", "QR-AC8", "https://pay.mock.local/ac8"));

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId()).as("AC8: should still return a new paymentId even when Redis is down").isNotNull();
        verify(txRepo, times(2)).save(any(PaymentTransaction.class));
    }

    // ============================================================
    // AC-HMAC-bad — bad/missing/malformed signatures → 401
    // ============================================================

    /** AC-HMAC-bad-1: secret set + wrong signature → INVALID_CALLBACK_SIGNATURE 401.
     *  Note: HMAC check fires BEFORE txRepo.findByGatewayOrderId, so no DB stub needed. */
    @Test
    void acHmacBad1_secretSet_wrongSignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);

        // No txRepo stub needed: verifyCallbackAuth is called first (bypassHmac=false)
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-BAD", "SUCCESS", 150_000L, "wrongsignaturehex00ff");

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.handleCallback(req, false));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC-HMAC-bad-2: secret set + null signature → INVALID_CALLBACK_SIGNATURE 401. */
    @Test
    void acHmacBad2_secretSet_nullSignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);

        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-NULL-SIG", "SUCCESS", 150_000L, null);

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.handleCallback(req, false));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC-HMAC-bad-3: secret set + empty string signature → INVALID_CALLBACK_SIGNATURE 401. */
    @Test
    void acHmacBad3_secretSet_emptySignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);

        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-EMPTY-SIG", "SUCCESS", 150_000L, "");

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.handleCallback(req, false));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC-HMAC-bad-4: secret set + invalid hex format signature → INVALID_CALLBACK_SIGNATURE 401. */
    @Test
    void acHmacBad4_secretSet_invalidHexSignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);

        // "ZZZZZZZZ" is not valid hex
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-BAD-HEX", "SUCCESS", 150_000L, "ZZZZZZZZ");

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.handleCallback(req, false));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ============================================================
    // AC-HMAC-good — correct HMAC passes verification
    // ============================================================

    /** AC-HMAC-good: correct HMAC-SHA256 of canonical message → no exception, tx saved + outbox saved. */
    @Test
    void acHmacGood_correctSignature_processesCallbackSuccessfully() throws Exception {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);

        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "GW-ORDER-GOOD";
        String gwTxn = "GW-TXN-GOOD";
        String status = "SUCCESS";
        Long amount = 200_000L;

        String correctSig = computeHmac(TEST_SECRET, gwOrderId, gwTxn, status, amount);

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);
        tx.setAmount(amount);

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenReturn(tx);
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CallbackRequest req = new CallbackRequest(gwOrderId, gwTxn, status, amount, correctSig);

        // Must NOT throw
        paymentService.handleCallback(req, false);

        verify(txRepo, times(1)).save(any());
        verify(outboxRepo, times(1)).save(any());
    }

    // ============================================================
    // AC-HMAC-failclosed — requireSignature=true + empty secret → 503
    // ============================================================

    /** AC-HMAC-failclosed: requireSignature=true + callbackSecret empty → SERVICE_UNAVAILABLE 503.
     *  Note: verifyCallbackAuth fires before DB lookup, no txRepo stub needed. */
    @Test
    void acHmacFailClosed_requireSignatureTrue_emptySecret_throws503() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", "");
        ReflectionTestUtils.setField(paymentService, "requireSignature", true);

        // No DB stub: 503 is thrown inside verifyCallbackAuth before any DB call
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-FC", "SUCCESS", 150_000L, "somesig");

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.handleCallback(req, false));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ============================================================
    // AC-amount — amount mismatch → 400 CALLBACK_AMOUNT_MISMATCH
    // ============================================================

    /** AC-amount: callback.amount != tx.amount → CALLBACK_AMOUNT_MISMATCH 400, no txRepo.save, no outbox.save. */
    @Test
    void acAmount_mismatch_throws400_noTransitionNoOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);
        // tx.amount = 150_000L; callback.amount = 200_000L → mismatch

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-AMT", "SUCCESS", 200_000L, null);

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.handleCallback(req, true));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CALLBACK_AMOUNT_MISMATCH);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

        // No transition, no outbox
        verify(txRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
    }

    // ============================================================
    // AC-devsim-sep — bypassHmac=true skips verify; bypassHmac=false enforces
    // ============================================================

    /** AC-devsim-sep-1: bypassHmac=true + secret set + wrong sig → DOES NOT throw (bypass works). */
    @Test
    void acDevsimSep1_bypassHmacTrue_secretSet_wrongSig_doesNotThrow() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);

        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenReturn(tx);
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // secret is set, sig is wrong — but bypassHmac=true skips HMAC check
        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-BYPASS", "SUCCESS", 150_000L, "totallywrongsig");

        // Must NOT throw
        paymentService.handleCallback(req, true);

        verify(outboxRepo, times(1)).save(any());
    }

    /** AC-devsim-sep-2: bypassHmac=false + secret set + wrong sig → throws 401 (devSim does NOT disable real webhook verify).
     *  No DB stub needed: HMAC check fires first. */
    @Test
    void acDevsimSep2_bypassHmacFalse_secretSet_wrongSig_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);

        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-REAL", "SUCCESS", 150_000L, "totallywrongsig");

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.handleCallback(req, false));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ============================================================
    // AC1-cache stale: cache hit but txRepo.findById empty → fall through to DB
    // ============================================================

    /** AC1-stale: cache returns paymentId but txRepo.findById returns empty (stale entry) → fall through to DB path. */
    @Test
    void ac1_staleCache_txNotFound_fallsThroughToDb() {
        UUID orderId = UUID.randomUUID();
        UUID stalePaymentId = UUID.randomUUID();
        String idemKey = "key-stale";

        // Stale cache entry points to a deleted tx
        when(idempotencyCache.get(idemKey)).thenReturn(Optional.of(stalePaymentId));
        when(txRepo.findById(stalePaymentId)).thenReturn(Optional.empty());

        // DB also has no existing tx (fresh path)
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());
        when(txRepo.save(any(PaymentTransaction.class))).thenAnswer(inv -> {
            PaymentTransaction t = inv.getArgument(0);
            if (t.getCreatedAt() == null) t.setCreatedAt(Instant.now());
            if (t.getUpdatedAt() == null) t.setUpdatedAt(Instant.now());
            return t;
        });
        when(sePayClient.createQr(any(), any(long.class), any(), any()))
                .thenReturn(new CreateQrResult("MOCK-STALE-GW", "QR", "https://pay.mock.local/stale"));

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId())
                .as("AC1-stale: must create new tx when cache entry is stale")
                .isNotNull()
                .isNotEqualTo(stalePaymentId);

        verify(sePayClient, times(1)).createQr(any(), any(long.class), any(), any());
    }

    // ============================================================
    // AC2e: successful callback → both txRepo.save and outboxRepo.save called
    // ============================================================

    /** AC2e: PENDING tx + SUCCESS callback → state transition to SUCCESS + outbox with PaymentSucceeded. */
    @Test
    void ac2e_pendingTx_successCallback_transitionsAndSavesOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);

        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenReturn(tx);
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-OK", "SUCCESS", 150_000L, null);

        paymentService.handleCallback(req, true);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.SUCCESS.name());

        ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PaymentSucceeded");
    }
}
