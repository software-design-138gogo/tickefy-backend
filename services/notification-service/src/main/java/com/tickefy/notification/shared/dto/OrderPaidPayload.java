package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code OrderPaid} integration event.
 *
 * <p>See: docs/contracts/common/event-envelope.md §14.2
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderPaidPayload {

    private UUID orderId;

    private UUID userId;

    private UUID concertId;

    private UUID reservationId;

    private List<OrderItemPayload> items;

    /** Total amount in VND (integer). */
    private Long totalAmount;

    private Instant paidAt;

    /** Nested DTO for each order line item. */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItemPayload {

        private UUID orderItemId;

        private UUID ticketTypeId;

        private String ticketTypeName;

        private Integer quantity;

        /** Unit price in VND. */
        private Long unitPrice;
    }
}
