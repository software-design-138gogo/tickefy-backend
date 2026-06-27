package com.tickefy.payment.modules.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Refund trigger from the order-side refund worker (mảnh [5]).
 *
 * @param orderId the order whose settled payment to refund
 * @param refundRequestId order-side idempotency key ("refund-"+orderId); UNIQUE-guarded
 * @param amount expected refund amount (VND, integer) — validated == settled tx.amount (G2)
 */
public record RefundPaymentRequest(
        @NotNull UUID orderId,
        @NotBlank @Size(max = 100) String refundRequestId,
        @NotNull @Positive Long amount) {}
