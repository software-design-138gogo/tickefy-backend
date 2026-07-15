package com.tickefy.eticket.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Batch event published after an OrderPaid message has issued tickets.
 *
 * Routing key: tickets.issued
 * Exchange: tickefy.exchange
 */
public record TicketsIssuedEvent(
        String messageId,
        String eventType,
        String eventVersion,
        String source,
        Instant occurredAt,
        String correlationId,
        String causationId,
        Payload payload
) {
    public static TicketsIssuedEvent from(
            OrderPaidEvent event,
            OrderPaidEvent.Payload sourcePayload,
            Instant issuedAt,
            List<TicketItem> tickets) {
        String parentMessageId = nonBlank(event.messageId(), sourcePayload.orderId());
        String messageId = UUID.nameUUIDFromBytes(
                ("tickets-issued:" + parentMessageId).getBytes(StandardCharsets.UTF_8)).toString();
        String correlationId = nonBlank(event.correlationId(), parentMessageId);

        return new TicketsIssuedEvent(
                messageId,
                "TicketsIssued",
                "1.0",
                "ticket-service",
                issuedAt,
                correlationId,
                event.messageId(),
                new Payload(
                        sourcePayload.orderId(),
                        sourcePayload.userId(),
                        sourcePayload.concertId(),
                        issuedAt,
                        List.copyOf(tickets)));
    }

    private static String nonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    public record Payload(
            String orderId,
            String userId,
            String concertId,
            Instant issuedAt,
            List<TicketItem> tickets
    ) {}

    public record TicketItem(
            String ticketId,
            String orderItemId,
            String ticketTypeId,
            String ticketTypeName,
            String status
    ) {}
}
