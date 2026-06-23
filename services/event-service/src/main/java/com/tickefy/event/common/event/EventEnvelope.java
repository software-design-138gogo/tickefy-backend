package com.tickefy.event.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope {
    private String messageId;
    private String eventType;
    private String eventVersion;
    private String source;
    private String occurredAt;
    private String correlationId;
    private UUID causationId;
    private JsonNode payload;
}
