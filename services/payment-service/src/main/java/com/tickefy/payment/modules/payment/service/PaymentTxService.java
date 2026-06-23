package com.tickefy.payment.modules.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
import com.tickefy.payment.modules.payment.cache.PaymentIdempotencyCache;
import com.tickefy.payment.modules.payment.dto.CreatePaymentResponse;
import com.tickefy.payment.modules.payment.entity.OutboxEntity;
import com.tickefy.payment.modules.payment.entity.PaymentStatus;
import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import com.tickefy.payment.modules.payment.gateway.SePayClient.CreateQrResult;
import com.tickefy.payment.modules.payment.repository.OutboxRepository;
import com.tickefy.payment.modules.payment.repository.PaymentTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proxy bean cung cấp TX ngắn cho createTransaction TX-split (§5 plan 2.7).
 * PaymentService gọi bean này CROSS-BEAN qua Spring proxy — @Transactional áp đúng.
 * KHÔNG self-invoke.
 */
@Service
public class PaymentTxService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTxService.class);

    private final PaymentTransactionRepository txRepo;
    private final OutboxRepository outboxRepo;
    private final PaymentIdempotencyCache idempotencyCache;
    private final PaymentStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    @Value("${app.payment.expiry:PT15M}")
    private Duration paymentExpiry;

    @Value("${app.payment.provider:MOCK_SEPAY}")
    private String provider;

    public PaymentTxService(
            PaymentTransactionRepository txRepo,
            OutboxRepository outboxRepo,
            PaymentIdempotencyCache idempotencyCache,
            PaymentStateMachine stateMachine,
            ObjectMapper objectMapper) {
        this.txRepo = txRepo;
        this.outboxRepo = outboxRepo;
        this.idempotencyCache = idempotencyCache;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    /**
     * TX1: insert PaymentTransaction INITIATED, commit.
     * Ném DataIntegrityViolationException nếu race — caller bắt và re-read.
     */
    @Transactional
    public PaymentTransaction tx1InsertInitiated(
            UUID paymentId,
            UUID orderId,
            UUID userId,
            long amount,
            String currency,
            String idempotencyKey) {
        PaymentTransaction tx =
                PaymentTransaction.builder()
                        .id(paymentId)
                        .orderId(orderId)
                        .userId(userId)
                        .amount(amount)
                        .currency(currency)
                        .idempotencyKey(idempotencyKey)
                        .status(PaymentStatus.INITIATED.name())
                        .build();
        txRepo.save(tx);
        log.info("TX1 INITIATED paymentId={} orderId={}", paymentId, orderId);
        return tx;
    }

    /**
     * TX2: INITIATED -> PENDING + register afterCommit cache write.
     * Returns CreatePaymentResponse 201.
     */
    @Transactional
    public CreatePaymentResponse tx2SetPending(
            UUID paymentId,
            String idempotencyKey,
            CreateQrResult qr) {
        PaymentTransaction tx =
                txRepo.findById(paymentId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.INTERNAL_SERVER_ERROR,
                                                "TX2: payment not found paymentId=" + paymentId,
                                                HttpStatus.INTERNAL_SERVER_ERROR));

        PaymentStatus current = PaymentStatus.valueOf(tx.getStatus());
        stateMachine.assertTransition(current, PaymentStatus.PENDING);

        tx.setGatewayOrderId(qr.gatewayOrderId());
        tx.setStatus(PaymentStatus.PENDING.name());
        txRepo.save(tx);

        // Register afterCommit cache — TX active here
        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            idempotencyCache.put(idempotencyKey, paymentId);
                        }
                    });
        } catch (IllegalStateException e) {
            log.warn(
                    "No active TX for afterCommit registration, writing cache inline key={}",
                    idempotencyKey,
                    e);
            idempotencyCache.put(idempotencyKey, paymentId);
        }

        Instant expiresAt = tx.getCreatedAt().plus(paymentExpiry);
        log.info(
                "TX2 PENDING paymentId={} orderId={} gatewayOrderId={}",
                paymentId,
                tx.getOrderId(),
                qr.gatewayOrderId());
        return new CreatePaymentResponse(paymentId, qr.paymentUrl(), qr.qrCodePayload(), expiresAt);
    }

    /**
     * TX3: INITIATED -> FAILED + insert outbox PaymentFailed reason=GATEWAY_ERROR, commit.
     * Caller PHẢI throw ApiException 503 SAU khi method này return (commit xong).
     */
    @Transactional
    public void tx3SetFailedGatewayError(UUID paymentId) {
        PaymentTransaction tx =
                txRepo.findById(paymentId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.INTERNAL_SERVER_ERROR,
                                                "TX3: payment not found paymentId=" + paymentId,
                                                HttpStatus.INTERNAL_SERVER_ERROR));

        PaymentStatus current = PaymentStatus.valueOf(tx.getStatus());
        stateMachine.assertTransition(current, PaymentStatus.FAILED);

        tx.setStatus(PaymentStatus.FAILED.name());
        txRepo.save(tx);

        String innerPayload = buildInnerPayload(tx, PaymentStatus.FAILED, "GATEWAY_ERROR");
        OutboxEntity outbox =
                OutboxEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateId(tx.getId())
                        .eventType("PaymentFailed")
                        .payload(innerPayload)
                        .status("PENDING")
                        .createdAt(Instant.now())
                        .build();
        outboxRepo.save(outbox);

        log.info(
                "TX3 FAILED paymentId={} orderId={} outbox={} reason=GATEWAY_ERROR",
                paymentId,
                tx.getOrderId(),
                outbox.getId());
    }

    /**
     * Reconciliation resolve: PENDING → SUCCESS.
     * Guard: nếu status != PENDING (callback đã resolve trước) → log skip + return (không 422).
     * Toàn bộ load+guard+set+outbox trong 1 @Transactional.
     */
    @Transactional
    public void resolveSuccess(UUID paymentId, String gatewayTransactionId) {
        PaymentTransaction tx =
                txRepo.findById(paymentId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.INTERNAL_SERVER_ERROR,
                                                "resolveSuccess: payment not found paymentId=" + paymentId,
                                                HttpStatus.INTERNAL_SERVER_ERROR));

        if (!"PENDING".equals(tx.getStatus())) {
            log.info(
                    "recon skip resolveSuccess status!=PENDING tx={} status={}",
                    tx.getId(),
                    tx.getStatus());
            return;
        }

        stateMachine.assertTransition(PaymentStatus.PENDING, PaymentStatus.SUCCESS);

        tx.setStatus(PaymentStatus.SUCCESS.name());
        if (gatewayTransactionId != null && tx.getGatewayTransactionId() == null) {
            tx.setGatewayTransactionId(gatewayTransactionId);
        }

        try {
            txRepo.save(tx);
        } catch (DataIntegrityViolationException e) {
            // uq_payment_gateway_txn: callback chen trước → no-op (Lớp B)
            org.hibernate.exception.ConstraintViolationException cve = unwrapConstraintViolation(e);
            boolean isDupGatewayTxn = (cve != null)
                    ? "uq_payment_gateway_txn".equals(cve.getConstraintName())
                    : (e.getMessage() != null && e.getMessage().contains("uq_payment_gateway_txn"));
            if (isDupGatewayTxn) {
                log.info(
                        "resolveSuccess dedup uq_payment_gateway_txn tx={} gatewayTransactionId={} — no-op",
                        paymentId,
                        gatewayTransactionId);
                return;
            }
            throw e;
        }

        String innerPayload = buildInnerPayload(tx, PaymentStatus.SUCCESS, null);
        OutboxEntity outbox =
                OutboxEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateId(tx.getId())
                        .eventType("PaymentSucceeded")
                        .payload(innerPayload)
                        .status("PENDING")
                        .createdAt(Instant.now())
                        .build();
        outboxRepo.save(outbox);

        log.info(
                "resolveSuccess tx={} orderId={} -> SUCCESS outbox={}",
                tx.getId(),
                tx.getOrderId(),
                outbox.getId());
    }

    /**
     * Reconciliation resolve: PENDING → FAILED.
     * Guard: nếu status != PENDING → log skip + return.
     */
    @Transactional
    public void resolveFailed(UUID paymentId, String reason) {
        PaymentTransaction tx =
                txRepo.findById(paymentId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.INTERNAL_SERVER_ERROR,
                                                "resolveFailed: payment not found paymentId=" + paymentId,
                                                HttpStatus.INTERNAL_SERVER_ERROR));

        if (!"PENDING".equals(tx.getStatus())) {
            log.info(
                    "recon skip resolveFailed status!=PENDING tx={} status={}",
                    tx.getId(),
                    tx.getStatus());
            return;
        }

        stateMachine.assertTransition(PaymentStatus.PENDING, PaymentStatus.FAILED);

        tx.setStatus(PaymentStatus.FAILED.name());
        txRepo.save(tx);

        String innerPayload = buildInnerPayload(tx, PaymentStatus.FAILED, reason);
        OutboxEntity outbox =
                OutboxEntity.builder()
                        .id(UUID.randomUUID())
                        .aggregateId(tx.getId())
                        .eventType("PaymentFailed")
                        .payload(innerPayload)
                        .status("PENDING")
                        .createdAt(Instant.now())
                        .build();
        outboxRepo.save(outbox);

        log.info(
                "resolveFailed tx={} orderId={} -> FAILED reason={} outbox={}",
                tx.getId(),
                tx.getOrderId(),
                reason,
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

    String buildInnerPayload(PaymentTransaction tx, PaymentStatus status, String reason) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("orderId", tx.getOrderId().toString());
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
                payload.put("reason", reason);
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
}
