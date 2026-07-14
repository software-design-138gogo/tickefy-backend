package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code OrderCancelled} integration event (rk {@code order.cancelled}).
 *
 * <p>Mirrors order-service {@code OrderEvents.OrderReleasePayload} (same shape as
 * {@code order.expired} and {@code order.payment.failed}; discriminated by envelope
 * {@code eventType}).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderCancelledPayload {

    private UUID orderId;

    private UUID userId;

    /** Optional reason supplied by the user or system when cancelling the order. */
    private String reason;

    private List<OrderCancelledItem> items;

    /** Nested DTO for each released order line item. */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderCancelledItem {

        private UUID ticketTypeId;

        private Integer quantity;
    }
}
