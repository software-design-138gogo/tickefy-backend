package com.tickefy.payment.modules.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentTransactionRepository txRepo;
    private final OutboxRepository outboxRepo;
    private final PaymentGatewayClient paymentGatewayClient;
    private final ObjectMapper objectMapper;
    private final PaymentIdempotencyCache idempotencyCache;
    private final PaymentTxService paymentTxService;
    private final PaymentStateMachine stateMachine;

    @Value("${app.payment.expiry:PT15M}")
    private Duration paymentExpiry;

    @Value("${app.payment.provider:MOCK_SEPAY}")
    private String provider;

    @Value("${app.payment.callback.secret:}")
    private String callbackSecret;

    @Value("${app.payment.callback.require-signature:false}")
    private boolean requireSignature;

    public PaymentService(
            PaymentTransactionRepository txRepo,
            OutboxRepository outboxRepo,
            PaymentGatewayClient paymentGatewayClient,
            ObjectMapper objectMapper,
            PaymentIdempotencyCache idempotencyCache,
            PaymentTxService paymentTxService,
            PaymentStateMachine stateMachine) {
        this.txRepo = txRepo;
        this.outboxRepo = outboxRepo;
        this.paymentGatewayClient = paymentGatewayClient;
        this.objectMapper = objectMapper;
        this.idempotencyCache = idempotencyCache;
        this.paymentTxService = paymentTxService;
        this.stateMachine = stateMachine;
    }

    /**
     * TX-split createTransaction (§5 plan 2.7):
     * pre (non-TX): cache fast-path + DB dedup
     * TX1 (proxy bean): insert INITIATED -> commit
     * gateway call OUTSIDE any TX (CB bọc)
     *   OK  -> TX2 (proxy bean): INITIATED->PENDING + afterCommit cache -> return 201
     *   Fail-> TX3 (proxy bean): INITIATED->FAILED + outbox GATEWAY_ERROR -> commit -> throw 503
     */
    public CreatePaymentResponse createTransaction(CreatePaymentRequest req) {
        // Pre (non-TX): Redis fast-path
        var cached = idempotencyCache.get(req.idempotencyKey());
        if (cached.isPresent()) {
            var txOpt = txRepo.findById(cached.get());
            if (txOpt.isPresent()) {
                PaymentTransaction tx = txOpt.get();
                log.info(
                        "Cache hit idempotency: key={} paymentId={}",
                        req.idempotencyKey(),
                        tx.getId());
                Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
                return new CreatePaymentResponse(tx.getId(), null, null, expiresAt);
            }
            log.warn(
                    "Stale idempotency cache entry key={} paymentId={}, falling through to DB",
                    req.idempotencyKey(),
                    cached.get());
        }

        // Pre (non-TX): DB-first dedup
        var existing = txRepo.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            PaymentTransaction tx = existing.get();
            log.info(
                    "Idempotent createTransaction (DB hit): key={} existing paymentId={}",
                    req.idempotencyKey(),
                    tx.getId());
            idempotencyCache.put(req.idempotencyKey(), tx.getId());
            Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
            return new CreatePaymentResponse(tx.getId(), null, null, expiresAt);
        }

        UUID paymentId = UUID.randomUUID();

        // TX1: insert INITIATED, commit (DataIntegrityViolation race -> re-read)
        try {
            paymentTxService.tx1InsertInitiated(
                    paymentId,
                    req.orderId(),
                    req.userId(),
                    req.amount(),
                    req.currency(),
                    req.idempotencyKey());
        } catch (DataIntegrityViolationException ex) {
            log.warn("Race condition on idempotency_key={}, re-reading", req.idempotencyKey());
            PaymentTransaction tx =
                    txRepo.findByIdempotencyKey(req.idempotencyKey())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    ErrorCode.INTERNAL_SERVER_ERROR,
                                                    "Failed to resolve idempotency race",
                                                    HttpStatus.INTERNAL_SERVER_ERROR));
            idempotencyCache.put(req.idempotencyKey(), tx.getId());
            Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
            return new CreatePaymentResponse(tx.getId(), null, null, expiresAt);
        }

        // Gateway call OUTSIDE any TX (CB in PaymentGatewayClient proxy — cross-bean)
        CreateQrResult qr;
        try {
            qr = paymentGatewayClient.createQr(
                    paymentId, req.amount(), req.currency(), req.orderId());
        } catch (ApiException gatewayEx) {
            // CB fallback or real gateway error -> TX3: INITIATED->FAILED + outbox GATEWAY_ERROR
            log.warn(
                    "Gateway call failed paymentId={}, running TX3 FAILED",
                    paymentId,
                    gatewayEx);
            paymentTxService.tx3SetFailedGatewayError(paymentId);
            // TX3 committed -> now throw 503 (FAILED+outbox persisted)
            throw gatewayEx;
        }

        // TX2: INITIATED->PENDING + afterCommit cache -> return 201
        return paymentTxService.tx2SetPending(paymentId, req.idempotencyKey(), qr);
    }

    @Transactional
    public void handleCallback(CallbackRequest req, boolean bypassHmac) {
        if (!bypassHmac) {
            verifyCallbackAuth(req);
        }

        PaymentTransaction tx =
                txRepo.findByGatewayOrderId(req.gatewayOrderId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.PAYMENT_NOT_FOUND,
                                                "Payment transaction not found for gatewayOrderId: "
                                                        + req.gatewayOrderId(),
                                                HttpStatus.NOT_FOUND));

        if (req.amount() != null && !req.amount().equals(tx.getAmount())) {
            log.warn(
                    "callback amount mismatch tx={} expected={} got={}",
                    tx.getId(),
                    tx.getAmount(),
                    req.amount());
            throw new ApiException(
                    ErrorCode.CALLBACK_AMOUNT_MISMATCH,
                    "Callback amount does not match transaction amount",
                    HttpStatus.BAD_REQUEST);
        }

        // State-guard terminal (2.6 idempotency — no-op return, NOT 422)
        PaymentStatus currentStatus = PaymentStatus.valueOf(tx.getStatus());
        if (currentStatus == PaymentStatus.SUCCESS
                || currentStatus == PaymentStatus.FAILED
                || currentStatus == PaymentStatus.REFUNDED) {
            log.info(
                    "handleCallback no-op: tx={} already terminal status={}",
                    tx.getId(),
                    currentStatus);
            return;
        }

        PaymentStatus newStatus =
                "SUCCESS".equalsIgnoreCase(req.status()) ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;

        // Assert transition via state machine
        stateMachine.assertTransition(currentStatus, newStatus);

        tx.setStatus(newStatus.name());
        if (req.gatewayTransactionId() != null) {
            tx.setGatewayTransactionId(req.gatewayTransactionId());
        }

        try {
            ObjectNode gwResp = objectMapper.createObjectNode();
            gwResp.put("gatewayOrderId", req.gatewayOrderId());
            if (req.gatewayTransactionId() != null) {
                gwResp.put("gatewayTransactionId", req.gatewayTransactionId());
            }
            gwResp.put("status", req.status());
            if (req.amount() != null) {
                gwResp.put("amount", req.amount());
            }
            tx.setGatewayResponse(objectMapper.writeValueAsString(gwResp));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize gateway response", e);
        }

        // Lớp B dedup: uq_payment_gateway_txn duplicate webhook
        try {
            txRepo.save(tx);
        } catch (DataIntegrityViolationException e) {
            // §7: try Hibernate ConstraintViolationException for cleaner check
            org.hibernate.exception.ConstraintViolationException cve = unwrapConstraintViolation(e);
            boolean isDupGatewayTxn = (cve != null)
                    ? "uq_payment_gateway_txn".equals(cve.getConstraintName())
                    : (e.getMessage() != null && e.getMessage().contains("uq_payment_gateway_txn"));
            if (isDupGatewayTxn) {
                log.info(
                        "duplicate webhook (gateway_txn unique) gatewayTransactionId={} — no-op",
                        req.gatewayTransactionId());
                return;
            }
            throw e;
        }

        String eventType = newStatus == PaymentStatus.SUCCESS ? "PaymentSucceeded" : "PaymentFailed";
        String reason = newStatus == PaymentStatus.FAILED ? "PAYMENT_FAILED" : null;
        String innerPayload = paymentTxService.buildInnerPayload(tx, newStatus, reason);

        OutboxEntity outbox =
                OutboxEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateId(tx.getId())
                        .eventType(eventType)
                        .payload(innerPayload)
                        .status("PENDING")
                        .createdAt(Instant.now())
                        .build();
        outboxRepo.save(outbox);

        log.info(
                "handleCallback tx={} orderId={} -> {} outbox={}",
                tx.getId(),
                tx.getOrderId(),
                newStatus,
                outbox.getId());
    }

    private org.hibernate.exception.ConstraintViolationException unwrapConstraintViolation(
            DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                return cve;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private void verifyCallbackAuth(CallbackRequest req) {
        boolean secretEmpty = callbackSecret == null || callbackSecret.isEmpty();
        if (!secretEmpty) {
            verifyHmac(req);
        } else if (requireSignature) {
            log.error("callback secret missing but required (app.payment.callback.require-signature=true)");
            throw new ApiException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "callback secret missing but required",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            log.warn("HMAC verification skipped: callback secret not configured (dev mode)");
        }
    }

    private void verifyHmac(CallbackRequest req) {
        String sig = req.signature();
        if (sig == null || sig.isEmpty()) {
            log.warn("HMAC verification failed: signature missing for gatewayOrderId={}", req.gatewayOrderId());
            throw new ApiException(
                    ErrorCode.INVALID_CALLBACK_SIGNATURE,
                    "Invalid callback signature",
                    HttpStatus.UNAUTHORIZED);
        }

        String message =
                (req.gatewayOrderId() != null ? req.gatewayOrderId() : "")
                        + "|"
                        + (req.gatewayTransactionId() != null ? req.gatewayTransactionId() : "")
                        + "|"
                        + (req.status() != null ? req.status() : "")
                        + "|"
                        + (req.amount() != null ? req.amount() : "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec =
                    new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            byte[] parsedSig;
            try {
                parsedSig = HexFormat.of().parseHex(sig.toLowerCase());
            } catch (IllegalArgumentException e) {
                log.warn("HMAC verification failed: invalid signature format for gatewayOrderId={}", req.gatewayOrderId());
                throw new ApiException(
                        ErrorCode.INVALID_CALLBACK_SIGNATURE,
                        "Invalid callback signature",
                        HttpStatus.UNAUTHORIZED);
            }

            if (!MessageDigest.isEqual(hmacBytes, parsedSig)) {
                log.warn("HMAC verification failed for gatewayOrderId={}", req.gatewayOrderId());
                throw new ApiException(
                        ErrorCode.INVALID_CALLBACK_SIGNATURE,
                        "Invalid callback signature",
                        HttpStatus.UNAUTHORIZED);
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC computation error", e);
            throw new ApiException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to verify callback signature",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
