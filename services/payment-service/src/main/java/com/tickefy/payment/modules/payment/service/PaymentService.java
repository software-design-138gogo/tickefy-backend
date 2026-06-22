package com.tickefy.payment.modules.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
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
    private final SePayClient sePayClient;
    private final ObjectMapper objectMapper;

    @Value("${app.payment.expiry:PT15M}")
    private Duration paymentExpiry;

    @Value("${app.payment.provider:MOCK_SEPAY}")
    private String provider;

    @Value("${app.payment.callback.secret:}")
    private String callbackSecret;

    @Value("${app.dev.payment-sim.enabled:false}")
    private boolean devSimEnabled;

    public PaymentService(
            PaymentTransactionRepository txRepo,
            OutboxRepository outboxRepo,
            SePayClient sePayClient,
            ObjectMapper objectMapper) {
        this.txRepo = txRepo;
        this.outboxRepo = outboxRepo;
        this.sePayClient = sePayClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreatePaymentResponse createTransaction(CreatePaymentRequest req) {
        // Idempotency: return existing if key already exists
        var existing = txRepo.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            PaymentTransaction tx = existing.get();
            log.info(
                    "Idempotent createTransaction: key={} existing paymentId={}",
                    req.idempotencyKey(),
                    tx.getId());
            Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
            return new CreatePaymentResponse(tx.getId(), null, null, expiresAt);
        }

        try {
            // Create INITIATED record
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

            Instant expiresAt = Instant.now().plus(paymentExpiry);
            log.info(
                    "createTransaction paymentId={} orderId={} gatewayOrderId={} status=PENDING",
                    paymentId,
                    req.orderId(),
                    qr.gatewayOrderId());
            return new CreatePaymentResponse(paymentId, qr.paymentUrl(), qr.qrCodePayload(), expiresAt);

        } catch (DataIntegrityViolationException ex) {
            // Race on unique idempotency_key — re-read and return existing
            log.warn(
                    "Race condition on idempotency_key={}, re-reading", req.idempotencyKey());
            PaymentTransaction tx =
                    txRepo.findByIdempotencyKey(req.idempotencyKey())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    ErrorCode.INTERNAL_SERVER_ERROR,
                                                    "Failed to resolve idempotency race",
                                                    HttpStatus.INTERNAL_SERVER_ERROR));
            Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
            return new CreatePaymentResponse(tx.getId(), null, null, expiresAt);
        }
    }

    @Transactional
    public void handleCallback(CallbackRequest req, boolean bypassHmac) {
        // 1. HMAC verification
        if (!bypassHmac && !devSimEnabled) {
            if (callbackSecret != null && !callbackSecret.isEmpty()) {
                verifyHmac(req);
            }
            // If secret empty: skip verify (dev mode without explicit bypass)
        }

        // 2. Find tx by gatewayOrderId
        PaymentTransaction tx =
                txRepo.findByGatewayOrderId(req.gatewayOrderId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.PAYMENT_NOT_FOUND,
                                                "Payment transaction not found for gatewayOrderId: "
                                                        + req.gatewayOrderId(),
                                                HttpStatus.NOT_FOUND));

        // 3. State-guard: terminal states are no-op
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

        // 4. Map status
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

        txRepo.save(tx);

        // 5. Insert outbox row in same TX
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
            // paymentTransactionId = tx.id (adjustment 1: use payment id, not gateway txn id)
            payload.put("paymentTransactionId", tx.getId().toString());
            // also include gatewayTransactionId as separate field if present
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

    private void verifyHmac(CallbackRequest req) {
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
            String computed = HexFormat.of().formatHex(hmacBytes);
            if (!computed.equalsIgnoreCase(req.signature())) {
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
