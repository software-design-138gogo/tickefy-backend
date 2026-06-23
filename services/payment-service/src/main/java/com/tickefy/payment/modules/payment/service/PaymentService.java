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
import com.tickefy.payment.modules.payment.gateway.SePayClient;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentTransactionRepository txRepo;
    private final OutboxRepository outboxRepo;
    private final SePayClient sePayClient;
    private final ObjectMapper objectMapper;
    private final PaymentIdempotencyCache idempotencyCache;

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
            SePayClient sePayClient,
            ObjectMapper objectMapper,
            PaymentIdempotencyCache idempotencyCache) {
        this.txRepo = txRepo;
        this.outboxRepo = outboxRepo;
        this.sePayClient = sePayClient;
        this.objectMapper = objectMapper;
        this.idempotencyCache = idempotencyCache;
    }

    @Transactional
    public CreatePaymentResponse createTransaction(CreatePaymentRequest req) {
        // Lớp A §2 bước 1: Redis fast-path
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
            // stale cache entry (tx rolled back) → fall-through to DB
            log.warn("Stale idempotency cache entry key={} paymentId={}, falling through to DB", req.idempotencyKey(), cached.get());
        }

        // §2 bước 2: DB-first dedup (giữ nguyên từ 2.5:71)
        var existing = txRepo.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            PaymentTransaction tx = existing.get();
            log.info(
                    "Idempotent createTransaction (DB hit): key={} existing paymentId={}",
                    req.idempotencyKey(),
                    tx.getId());
            // populate-on-miss: ghi cache cho lần sau
            idempotencyCache.put(req.idempotencyKey(), tx.getId());
            Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
            return new CreatePaymentResponse(tx.getId(), null, null, expiresAt);
        }

        try {
            // §2 bước 3: Tạo mới (giữ nguyên từ 2.5:82-111)
            UUID paymentId = UUID.randomUUID();
            PaymentTransaction tx =
                    PaymentTransaction.builder()
                            .id(paymentId)
                            .orderId(req.orderId())
                            .userId(req.userId())
                            .amount(req.amount())
                            .currency(req.currency())
                            .idempotencyKey(req.idempotencyKey())
                            .status(PaymentStatus.INITIATED.name())
                            .build();
            txRepo.save(tx);

            // Call gateway (in-process mock — no network, OK inside TX)
            CreateQrResult qr = sePayClient.createQr(paymentId, req.amount(), req.currency(), req.orderId());

            // Update to PENDING
            tx.setGatewayOrderId(qr.gatewayOrderId());
            tx.setStatus(PaymentStatus.PENDING.name());
            txRepo.save(tx);

            // Ghi cache afterCommit (TX boundary active tại đây — @Transactional :68)
            // Nếu registerSynchronization ném IllegalStateException (không có TX active) → inline fallback
            String idempotencyKey = req.idempotencyKey();
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        idempotencyCache.put(idempotencyKey, paymentId);
                    }
                });
            } catch (IllegalStateException e) {
                // Không có TX active (edge case) → inline put ngay (best-effort, self-heal qua DB)
                log.warn("No active transaction for afterCommit registration, writing cache inline for key={}", idempotencyKey, e);
                idempotencyCache.put(idempotencyKey, paymentId);
            }

            Instant expiresAt = Instant.now().plus(paymentExpiry);
            log.info(
                    "createTransaction paymentId={} orderId={} gatewayOrderId={} status=PENDING",
                    paymentId,
                    req.orderId(),
                    qr.gatewayOrderId());
            return new CreatePaymentResponse(paymentId, qr.paymentUrl(), qr.qrCodePayload(), expiresAt);

        } catch (DataIntegrityViolationException ex) {
            // Race on unique idempotency_key — re-read and return existing (giữ từ 2.5:113-127)
            log.warn("Race condition on idempotency_key={}, re-reading", req.idempotencyKey());
            PaymentTransaction tx =
                    txRepo.findByIdempotencyKey(req.idempotencyKey())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    ErrorCode.INTERNAL_SERVER_ERROR,
                                                    "Failed to resolve idempotency race",
                                                    HttpStatus.INTERNAL_SERVER_ERROR));
            // populate cache for next request
            idempotencyCache.put(req.idempotencyKey(), tx.getId());
            Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
            return new CreatePaymentResponse(tx.getId(), null, null, expiresAt);
        }
    }

    @Transactional
    public void handleCallback(CallbackRequest req, boolean bypassHmac) {
        // §4 bước 1: HMAC gate — tách devSim, gate chỉ dựa bypassHmac
        if (!bypassHmac) {
            verifyCallbackAuth(req);
        }

        // 2. Find tx by gatewayOrderId (giữ từ 2.5:141-149)
        PaymentTransaction tx =
                txRepo.findByGatewayOrderId(req.gatewayOrderId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.PAYMENT_NOT_FOUND,
                                                "Payment transaction not found for gatewayOrderId: "
                                                        + req.gatewayOrderId(),
                                                HttpStatus.NOT_FOUND));

        // §4 bước 4: amount-check (sau khi tìm tx, trước transition)
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

        // §3: State-guard terminal states (giữ từ 2.5:152-161) — Lớp B replay dedup
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

        // 4. Map status (giữ từ 2.5:164-170)
        PaymentStatus newStatus =
                "SUCCESS".equalsIgnoreCase(req.status()) ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;

        tx.setStatus(newStatus.name());
        if (req.gatewayTransactionId() != null) {
            tx.setGatewayTransactionId(req.gatewayTransactionId());
        }

        // Store gateway response
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

        // §3 Tầng 2: bắt unique vi phạm uq_payment_gateway_txn (2.5:188 + thêm catch)
        try {
            txRepo.save(tx);
        } catch (DataIntegrityViolationException e) {
            // Kiểm tra có phải vi phạm uq_payment_gateway_txn không
            String msg = e.getMessage();
            if (msg != null && msg.contains("uq_payment_gateway_txn")) {
                log.info(
                        "duplicate webhook (gateway_txn unique) gatewayTransactionId={} — no-op",
                        req.gatewayTransactionId());
                return; // KHÔNG insert outbox
            }
            // Lỗi unique khác → ném lại
            throw e;
        }

        // 5. Insert outbox row in same TX (giữ từ 2.5:194-203)
        // Chỉ đến đây nếu save(tx) thành công (không ném)
        String eventType = newStatus == PaymentStatus.SUCCESS ? "PaymentSucceeded" : "PaymentFailed";
        String innerPayload = buildInnerPayload(tx, newStatus);

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

    private String buildInnerPayload(PaymentTransaction tx, PaymentStatus status) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("orderId", tx.getOrderId().toString());
            // paymentTransactionId = tx.id (B2 contract giữ nguyên)
            payload.put("paymentTransactionId", tx.getId().toString());
            if (tx.getGatewayTransactionId() != null) {
                payload.put("gatewayTransactionId", tx.getGatewayTransactionId());
            }
            payload.put("status", status.name());
            payload.put("amount", tx.getAmount());
            payload.put("currency", tx.getCurrency());
            payload.put("provider", provider);
            if (status == PaymentStatus.SUCCESS) {
                payload.put("paidAt", Instant.now().toString());
            } else {
                payload.put("reason", "PAYMENT_FAILED");
            }
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to build inner payload for tx={}", tx.getId(), e);
            throw new ApiException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to serialize outbox payload",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * §4 bước 2: fail-closed HMAC auth gate.
     * secret nonempty → LUÔN verify (dù require=false).
     * require=true + secret rỗng → reject 503.
     * require=false + secret rỗng → skip + log.warn (dev).
     */
    private void verifyCallbackAuth(CallbackRequest req) {
        boolean secretEmpty = callbackSecret == null || callbackSecret.isEmpty();
        if (!secretEmpty) {
            // secret có → LUÔN verify (đóng fail-open 2.5)
            verifyHmac(req);
        } else if (requireSignature) {
            // require=true + secret rỗng → fail-closed
            log.error("callback secret missing but required (app.payment.callback.require-signature=true)");
            throw new ApiException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "callback secret missing but required",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            // require=false + secret rỗng → dev convenience skip
            log.warn("HMAC verification skipped: callback secret not configured (dev mode)");
        }
    }

    /**
     * §4 bước 3: constant-time HMAC verify.
     * sig null/empty/parse-fail → reject INVALID_CALLBACK_SIGNATURE 401.
     */
    private void verifyHmac(CallbackRequest req) {
        // §F.1 [CHỐT]: INVALID_CALLBACK_SIGNATURE → 401 (KHÔNG đổi 400)
        String sig = req.signature();
        if (sig == null || sig.isEmpty()) {
            log.warn("HMAC verification failed: signature missing for gatewayOrderId={}", req.gatewayOrderId());
            throw new ApiException(
                    ErrorCode.INVALID_CALLBACK_SIGNATURE,
                    "Invalid callback signature",
                    HttpStatus.UNAUTHORIZED);
        }

        // Build canonical message: gatewayOrderId|gatewayTransactionId|status|amount
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

            // §4 bước 3: constant-time compare via MessageDigest.isEqual
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
