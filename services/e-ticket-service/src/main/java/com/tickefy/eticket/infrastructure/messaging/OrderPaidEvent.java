package com.tickefy.eticket.infrastructure.messaging;

import java.util.List;

/**
 * Payload of the order.paid event published by order-service.
 * E-ticket-service consumes this to issue tickets for each order item.
 *
 * Fields mirror the order-service OrderPaidEvent contract.
 */
public record OrderPaidEvent(
        String orderId,
        String userId,
        String concertId,
        List<OrderItem> items
) {
    public record OrderItem(
            String orderItemId,
            String ticketTypeId,
            String zoneId,
            String ticketTypeName
    ) {}
}
