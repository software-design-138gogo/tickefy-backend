package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code OrderRefunded} integration event (rk {@code order.refunded}).
 *
 * <p>Mirrors order-service {@code OrderEvents.OrderRefundedPayload}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderRefundedPayload {

    private UUID orderId;

    private UUID userId;

    private UUID concertId;

    /** Refunded amount in VND (integer). */
    private Long refundAmount;

    private String paymentTransactionId;

    private Instant refundedAt;
}
