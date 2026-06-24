package com.tickefy.csvingestion.modules.csvimport.event;

import java.time.Instant;
import java.util.UUID;

/** Standard event envelope (CLAUDE §6.4): messageId + eventType + eventVersion + occurredAt + payload. */
public record EventEnvelope<T>(
        String messageId, String eventType, String eventVersion, String occurredAt, T payload) {

    public static <T> EventEnvelope<T> of(String eventType, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(), eventType, "1.0", Instant.now().toString(), payload);
    }
}
