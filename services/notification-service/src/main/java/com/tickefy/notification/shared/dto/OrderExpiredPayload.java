package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code OrderExpired} integration event (rk {@code order.expired}).
 *
 * <p>Mirrors order-service {@code OrderEvents.OrderReleasePayload} (shared shape with
 * {@code order.payment.failed}; discriminated by envelope {@code eventType}).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderExpiredPayload {

    private UUID orderId;

    private UUID userId;

    private List<OrderExpiredItem> items;

    /** Nested DTO for each released order line item. */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderExpiredItem {

        private UUID ticketTypeId;

        private Integer quantity;
    }
}
