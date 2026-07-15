package com.tickefy.eticket.infrastructure.messaging;

import java.time.Instant;

/**
 * Envelope consumed from event-service.
 *
 * Routing key: concert.cancelled
 */
public record ConcertCancelledEvent(
        String messageId,
        String eventType,
        String eventVersion,
        String source,
        String occurredAt,
        String correlationId,
        String causationId,
        Payload payload
) {
    public record Payload(
            String concertId,
            Instant cancelledAt,
            String reason
    ) {}
}
