package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard RabbitMQ event envelope used by all Tickefy integration events.
 *
 * <p>See: docs/contracts/common/event-envelope.md
 *
 * @param <T> the event-specific payload type
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventEnvelope<T> {

    /** UUID v4 — unique ID of this message occurrence. Used as idempotency key. */
    private UUID messageId;

    /** PascalCase name of the domain/integration event (e.g. "OrderPaid"). */
    private String eventType;

    /** Schema version of the payload (e.g. "1.0"). */
    private String eventVersion;

    /** Canonical name of the producer service (e.g. "order-service"). */
    private String source;

    /** ISO-8601 UTC timestamp of when the business event occurred. */
    private Instant occurredAt;

    /** Trace ID for the entire business flow across services. */
    private String correlationId;

    /**
     * MessageId of the parent message that directly caused this event. May be {@code null} if the
     * event originates from an HTTP request.
     */
    private UUID causationId;

    /** Event-specific business data. */
    private T payload;
}
