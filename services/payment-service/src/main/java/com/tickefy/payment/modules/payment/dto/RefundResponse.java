package com.tickefy.payment.modules.payment.dto;

import java.util.UUID;

/**
 * Successful-refund response (HTTP 200). Non-success outcomes (REJECTED 422 / gateway-down 503 /
 * no-success-tx 409-404 / amount-mismatch 422) are rendered as the shared error envelope by
 * GlobalExceptionHandler — the order worker branches on HTTP status + error.code.
 *
 * @param status always "REFUNDED" on the 200 path (incl. idempotent replay)
 * @param refundGatewayRef gateway refund reference
 * @param paymentTransactionId the refunded payment transaction id
 */
public record RefundResponse(String status, String refundGatewayRef, UUID paymentTransactionId) {}
