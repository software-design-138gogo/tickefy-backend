package com.tickefy.eticket.infrastructure.messaging;

import java.time.Instant;

/**
 * Event published by e-ticket-service after successfully issuing a ticket.
 * Consumed by notification-service (sends ticket email/push).
 *
 * Routing key: ticket.issued
 * Exchange: tickefy.exchange
 */
public record TicketIssuedEvent(
        String ticketId,
        String orderId,
        String orderItemId,
        String userId,
        String concertId,
        String qrToken,
        Instant issuedAt
) {}
