package com.tickefy.eticket.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published for notification-service to send 24h ticket reminders.
 *
 * Routing key: ticket.reminder-requested
 * Exchange: tickefy.exchange
 */
public record TicketReminderRequestedEvent(
        String messageId,
        String eventType,
        String eventVersion,
        String source,
        Instant occurredAt,
        String correlationId,
        String causationId,
        Payload payload
) {
    public static TicketReminderRequestedEvent from(
            ConcertUpcomingEvent event,
            ConcertUpcomingEvent.Payload sourcePayload,
            String userId,
            int ticketCount,
            Instant requestedAt) {
        String parentMessageId = nonBlank(event.messageId(), sourcePayload.concertId());
        String messageId = UUID.nameUUIDFromBytes(
                ("ticket-reminder-requested:" + parentMessageId + ":" + userId)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        String correlationId = nonBlank(event.correlationId(), parentMessageId);

        return new TicketReminderRequestedEvent(
                messageId,
                "TicketReminderRequested",
                "1.0",
                "ticket-service",
                requestedAt,
                correlationId,
                event.messageId(),
                new Payload(
                        userId,
                        sourcePayload.concertId(),
                        sourcePayload.concertTitle(),
                        sourcePayload.eventDateTime(),
                        ticketCount));
    }

    private static String nonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    public record Payload(
            String userId,
            String concertId,
            String concertTitle,
            Instant eventDateTime,
            int ticketCount
    ) {}
}
