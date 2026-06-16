package com.tickefy.eticket.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The order.paid event published by order-service (ENVELOPE per backend-service-workflow §10):
 * {@code {messageId, eventType, eventVersion, occurredAt, payload:{orderId,userId,concertId,paidAt,items}}}.
 * E-ticket-service consumes this to issue tickets for each order item.
 *
 * <p>{@code item.quantity} drives qty>1: an order item with quantity=N issues N tickets.
 *
 * <p>{@code ignoreUnknown=true}: tolerate any extra fields order-service may add so deserialization
 * does not fail on unknown properties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaidEvent(
        String messageId,
        String eventType,
        String eventVersion,
        String occurredAt,
        Payload payload
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            String orderId,
            String userId,
            String concertId,
            String paidAt,
            List<OrderItem> items
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItem(
            String orderItemId,
            String ticketTypeId,
            int quantity,
            String zoneId,
            String ticketTypeName
    ) {}
}
