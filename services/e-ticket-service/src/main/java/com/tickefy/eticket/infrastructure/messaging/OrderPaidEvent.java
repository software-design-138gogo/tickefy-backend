package com.tickefy.eticket.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Payload of the order.paid event published by order-service.
 * E-ticket-service consumes this to issue tickets for each order item.
 *
 * Fields mirror the order-service OrderPaidEvent contract.
 *
 * <p>{@code ignoreUnknown=true}: order-service publishes a flat order.paid body that also carries
 * envelope/extra fields (messageId, eventType, timestamp, paidAt, item.quantity) which this consumer
 * does not model. Tolerate them so deserialization does not fail on unknown properties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaidEvent(
        String orderId,
        String userId,
        String concertId,
        List<OrderItem> items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItem(
            String orderItemId,
            String ticketTypeId,
            String zoneId,
            String ticketTypeName
    ) {}
}
