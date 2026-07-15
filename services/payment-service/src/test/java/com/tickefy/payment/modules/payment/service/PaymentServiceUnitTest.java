package com.tickefy.payment.modules.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.tickefy.payment.modules.payment.gateway.PaymentGatewayClient;
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

/**
 * Unit tests for PaymentService (2.6 regression + 2.7 new ACs).
 * Adapted for TX-split: mocks PaymentTxService + PaymentGatewayClient + PaymentStateMachine.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PaymentServiceUnitTest {

    @Mock
    private PaymentTransactionRepository txRepo;

    @Mock
    private OutboxRepository outboxRepo;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private PaymentIdempotencyCache idempotencyCache;

    @Mock
    private PaymentTxService paymentTxService;

    @Mock
    private PaymentStateMachine stateMachine;

    private ObjectMapper objectMapper;
    private PaymentService paymentService;

    private static final String TEST_SECRET = "test-secret-for-hmac";
    private static final Duration EXPIRY = Duration.ofMinutes(15);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        paymentService = new PaymentService(
                txRepo, outboxRepo, paymentGatewayClient, objectMapper,
                idempotencyCache, paymentTxService, stateMachine);
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
    // AC-regress 2.6 — AC1 CACHE FAST-PATH
    // ============================================================

    /** AC1a: cache.get returns paymentId + txRepo.findById present -> return cached response, NEVER call gateway. */
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

        verify(paymentGatewayClient, never()).createQr(any(), anyLong(), any(), any());
        verify(txRepo, never()).findByIdempotencyKey(any());
    }

    /** AC1b: cache miss + DB hit -> populate cache, return existing paymentId, NEVER call gateway. */
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
        verify(paymentGatewayClient, never()).createQr(any(), anyLong(), any(), any());
    }

    /**
     * AC-regress-createTransaction-happy: gateway OK -> tx1 + tx2 called, returns PENDING response.
     */
    @Test
    void acRegressCreateTransactionHappy_gatewayOk_tx1AndTx2Called_returnsPending() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String idemKey = "key-happy";

        when(idempotencyCache.get(idemKey)).thenReturn(Optional.empty());
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());

        // tx1 inserts INITIATED (void-like, we return stub tx to simulate)
        PaymentTransaction initiatedTx = buildTx(paymentId, orderId, PaymentStatus.INITIATED, null);
        when(paymentTxService.tx1InsertInitiated(any(), eq(orderId), any(), eq(150_000L), eq("VND"), eq(idemKey)))
                .thenReturn(initiatedTx);

        // gateway succeeds
        CreateQrResult qr = new CreateQrResult("MOCK-GW-001", "QR-PAYLOAD", "https://pay.mock.local/x");
        when(paymentGatewayClient.createQr(any(), eq(150_000L), eq("VND"), eq(orderId)))
                .thenReturn(qr);

        // tx2 returns the final response
        Instant expiresAt = Instant.now().plus(EXPIRY);
        CreatePaymentResponse expected = new CreatePaymentResponse(paymentId, "https://pay.mock.local/x", "QR-PAYLOAD", expiresAt);
        when(paymentTxService.tx2SetPending(any(), eq(idemKey), eq(qr))).thenReturn(expected);

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId()).as("happy: must return paymentId from tx2").isNotNull();
        assertThat(resp.paymentUrl()).isEqualTo("https://pay.mock.local/x");

        verify(paymentTxService, times(1)).tx1InsertInitiated(any(), eq(orderId), any(), eq(150_000L), eq("VND"), eq(idemKey));
        verify(paymentGatewayClient, times(1)).createQr(any(), anyLong(), any(), any());
        verify(paymentTxService, times(1)).tx2SetPending(any(), eq(idemKey), eq(qr));
        verify(paymentTxService, never()).tx3SetFailedGatewayError(any());
    }

    // ============================================================
    // AC-CB-fail-publish: gateway 503 -> tx3 called, re-throw 503
    // ============================================================

    /**
     * AC-CB-fail-publish: createTransaction when gateway throws ApiException 503
     * -> paymentTxService.tx3SetFailedGatewayError called once, exception re-thrown with 503.
     */
    @Test
    void acCbFailPublish_gatewayThrows503_tx3CalledAndExceptionRethrown() {
        UUID orderId = UUID.randomUUID();
        String idemKey = "key-cb-fail";

        when(idempotencyCache.get(idemKey)).thenReturn(Optional.empty());
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());

        PaymentTransaction initiatedTx = buildTx(UUID.randomUUID(), orderId, PaymentStatus.INITIATED, null);
        when(paymentTxService.tx1InsertInitiated(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(initiatedTx);

        ApiException gatewayEx = new ApiException(
                ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Payment gateway unavailable",
                HttpStatus.SERVICE_UNAVAILABLE);
        when(paymentGatewayClient.createQr(any(), anyLong(), any(), any()))
                .thenThrow(gatewayEx);

        ApiException thrown = assertThrows(ApiException.class,
                () -> paymentService.createTransaction(buildCreateReq(orderId, idemKey)));

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(paymentTxService, times(1)).tx3SetFailedGatewayError(any());
        verify(paymentTxService, never()).tx2SetPending(any(), any(), any());
    }

    // ============================================================
    // AC-regress 2.6 — AC2 CALLBACK DEDUP
    // ============================================================

    /** AC2a: callback on already-SUCCESS tx -> no-op, NEVER call outboxRepo.save. */
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

    /** AC2b: callback on already-FAILED tx -> no-op, NEVER call outboxRepo.save. */
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

    /** AC2c: txRepo.save throws DataIntegrityViolationException with "uq_payment_gateway_txn" -> no-op, NEVER outboxRepo.save. */
    @Test
    void ac2c_duplicateGatewayTxnUniqueViolation_isNoOp_neverSavesOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);
        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenThrow(
                new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint \"uq_payment_gateway_txn\""));

        // stateMachine.assertTransition is void — by default mock does nothing (no throw) — PENDING->SUCCESS is valid

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-DUPE", "SUCCESS", 150_000L, null);
        paymentService.handleCallback(req, true);

        verify(outboxRepo, never()).save(any());
    }

    /** AC2d: DataIntegrityViolationException with DIFFERENT message -> rethrow. */
    @Test
    void ac2d_otherIntegrityViolation_rethrows() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);
        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenThrow(
                new DataIntegrityViolationException("ERROR: duplicate key on some_other_constraint"));

        // stateMachine.assertTransition mock does nothing by default

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-OTHER", "SUCCESS", 150_000L, null);

        assertThrows(DataIntegrityViolationException.class,
                () -> paymentService.handleCallback(req, true),
                "AC2d: non-gateway-txn integrity violation must rethrow");
    }

    // ============================================================
    // AC-regress 2.6 — AC8 REDIS DOWN (service level)
    // ============================================================

    /** AC8-service: Redis down simulated -> service falls through to DB path, creates new tx. */
    @Test
    void ac8_redisDown_serviceFallsThroughToDb() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String idemKey = "key-ac8";

        when(idempotencyCache.get(idemKey)).thenReturn(Optional.empty());
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());

        PaymentTransaction initiatedTx = buildTx(paymentId, orderId, PaymentStatus.INITIATED, null);
        when(paymentTxService.tx1InsertInitiated(any(), eq(orderId), any(), eq(150_000L), eq("VND"), eq(idemKey)))
                .thenReturn(initiatedTx);

        CreateQrResult qr = new CreateQrResult("MOCK-GW-AC8", "QR-AC8", "https://pay.mock.local/ac8");
        when(paymentGatewayClient.createQr(any(), anyLong(), any(), any())).thenReturn(qr);

        Instant expiresAt = Instant.now().plus(EXPIRY);
        CreatePaymentResponse expected = new CreatePaymentResponse(paymentId, "https://pay.mock.local/ac8", "QR-AC8", expiresAt);
        when(paymentTxService.tx2SetPending(any(), eq(idemKey), eq(qr))).thenReturn(expected);

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId())
                .as("AC8: should return new paymentId even when Redis is down")
                .isNotNull();
        verify(paymentTxService, times(1)).tx1InsertInitiated(any(), any(), any(), anyLong(), any(), any());
        verify(paymentTxService, times(1)).tx2SetPending(any(), any(), any());
    }

    // ============================================================
    // AC-regress 2.6 — HMAC bad
    // ============================================================

    /** AC-HMAC-bad-1: secret set + wrong signature -> INVALID_CALLBACK_SIGNATURE 401. */
    @Test
    void acHmacBad1_secretSet_wrongSignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-BAD", "SUCCESS", 150_000L, "wrongsignaturehex00ff");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.handleCallback(req, false));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC-HMAC-bad-2: secret set + null signature -> INVALID_CALLBACK_SIGNATURE 401. */
    @Test
    void acHmacBad2_secretSet_nullSignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-NULL-SIG", "SUCCESS", 150_000L, null);
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.handleCallback(req, false));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC-HMAC-bad-3: secret set + empty string signature -> INVALID_CALLBACK_SIGNATURE 401. */
    @Test
    void acHmacBad3_secretSet_emptySignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-EMPTY-SIG", "SUCCESS", 150_000L, "");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.handleCallback(req, false));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC-HMAC-bad-4: secret set + invalid hex format signature -> INVALID_CALLBACK_SIGNATURE 401. */
    @Test
    void acHmacBad4_secretSet_invalidHexSignature_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-BAD-HEX", "SUCCESS", 150_000L, "ZZZZZZZZ");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.handleCallback(req, false));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ============================================================
    // AC-regress 2.6 — HMAC good
    // ============================================================

    /** AC-HMAC-good: correct HMAC-SHA256 -> no exception, tx saved + outbox saved. */
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
        when(paymentTxService.buildInnerPayload(any(), eq(PaymentStatus.SUCCESS), eq(null)))
                .thenReturn("{\"orderId\":\"" + orderId + "\",\"status\":\"SUCCESS\"}");
        // stateMachine.assertTransition mock does nothing by default

        CallbackRequest req = new CallbackRequest(gwOrderId, gwTxn, status, amount, correctSig);
        paymentService.handleCallback(req, false);

        verify(txRepo, times(1)).save(any());
        verify(outboxRepo, times(1)).save(any());
    }

    // ============================================================
    // AC-regress 2.6 — HMAC fail-closed
    // ============================================================

    /** AC-HMAC-failclosed: requireSignature=true + callbackSecret empty -> SERVICE_UNAVAILABLE 503. */
    @Test
    void acHmacFailClosed_requireSignatureTrue_emptySecret_throws503() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", "");
        ReflectionTestUtils.setField(paymentService, "requireSignature", true);
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-FC", "SUCCESS", 150_000L, "somesig");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.handleCallback(req, false));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ============================================================
    // AC-regress 2.6 — amount mismatch
    // ============================================================

    /** AC-amount: callback.amount != tx.amount -> CALLBACK_AMOUNT_MISMATCH 400. */
    @Test
    void acAmount_mismatch_throws400_noTransitionNoOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);
        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-AMT", "SUCCESS", 200_000L, null);
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.handleCallback(req, true));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CALLBACK_AMOUNT_MISMATCH);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(txRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
    }

    // ============================================================
    // AC-regress 2.6 — bypassHmac
    // ============================================================

    /** AC-devsim-sep-1: bypassHmac=true + secret set + wrong sig -> DOES NOT throw. */
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
        when(paymentTxService.buildInnerPayload(any(), eq(PaymentStatus.SUCCESS), eq(null)))
                .thenReturn("{\"status\":\"SUCCESS\"}");
        // stateMachine.assertTransition mock does nothing by default

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-BYPASS", "SUCCESS", 150_000L, "totallywrongsig");
        paymentService.handleCallback(req, true);

        verify(outboxRepo, times(1)).save(any());
    }

    /** AC-devsim-sep-2: bypassHmac=false + secret set + wrong sig -> throws 401. */
    @Test
    void acDevsimSep2_bypassHmacFalse_secretSet_wrongSig_throws401() {
        ReflectionTestUtils.setField(paymentService, "callbackSecret", TEST_SECRET);
        CallbackRequest req = new CallbackRequest("ANY-GW-ORDER", "GW-TXN-REAL", "SUCCESS", 150_000L, "totallywrongsig");
        ApiException ex = assertThrows(ApiException.class, () -> paymentService.handleCallback(req, false));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CALLBACK_SIGNATURE);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ============================================================
    // AC-regress 2.6 — stale cache
    // ============================================================

    /** AC1-stale: cache returns paymentId but txRepo.findById returns empty -> fall through to DB. */
    @Test
    void ac1_staleCache_txNotFound_fallsThroughToDb() {
        UUID orderId = UUID.randomUUID();
        UUID stalePaymentId = UUID.randomUUID();
        UUID newPaymentId = UUID.randomUUID();
        String idemKey = "key-stale";

        when(idempotencyCache.get(idemKey)).thenReturn(Optional.of(stalePaymentId));
        when(txRepo.findById(stalePaymentId)).thenReturn(Optional.empty());
        when(txRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());

        PaymentTransaction initiatedTx = buildTx(newPaymentId, orderId, PaymentStatus.INITIATED, null);
        when(paymentTxService.tx1InsertInitiated(any(), eq(orderId), any(), eq(150_000L), eq("VND"), eq(idemKey)))
                .thenReturn(initiatedTx);

        CreateQrResult qr = new CreateQrResult("MOCK-STALE-GW", "QR", "https://pay.mock.local/stale");
        when(paymentGatewayClient.createQr(any(), anyLong(), any(), any())).thenReturn(qr);

        Instant expiresAt = Instant.now().plus(EXPIRY);
        CreatePaymentResponse expected = new CreatePaymentResponse(newPaymentId, "https://pay.mock.local/stale", "QR", expiresAt);
        when(paymentTxService.tx2SetPending(any(), eq(idemKey), eq(qr))).thenReturn(expected);

        CreatePaymentResponse resp = paymentService.createTransaction(buildCreateReq(orderId, idemKey));

        assertThat(resp.paymentId())
                .as("AC1-stale: must create new tx when cache entry is stale")
                .isNotNull()
                .isNotEqualTo(stalePaymentId);

        verify(paymentGatewayClient, times(1)).createQr(any(), anyLong(), any(), any());
    }

    // ============================================================
    // AC-regress 2.6 — AC2e successful callback
    // ============================================================

    /** AC2e: PENDING tx + SUCCESS callback -> state transition to SUCCESS + outbox with PaymentSucceeded. */
    @Test
    void ac2e_pendingTx_successCallback_transitionsAndSavesOutbox() {
        UUID txId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String gwOrderId = "MOCK-" + txId;

        PaymentTransaction tx = buildTx(txId, orderId, PaymentStatus.PENDING, gwOrderId);
        when(txRepo.findByGatewayOrderId(gwOrderId)).thenReturn(Optional.of(tx));
        when(txRepo.save(any())).thenReturn(tx);
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentTxService.buildInnerPayload(any(), eq(PaymentStatus.SUCCESS), eq(null)))
                .thenReturn("{\"status\":\"SUCCESS\"}");
        // stateMachine.assertTransition mock does nothing by default

        CallbackRequest req = new CallbackRequest(gwOrderId, "GW-TXN-OK", "SUCCESS", 150_000L, null);
        paymentService.handleCallback(req, true);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.SUCCESS.name());

        ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PaymentSucceeded");
    }
}
