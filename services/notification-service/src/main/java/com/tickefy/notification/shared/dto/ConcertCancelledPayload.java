package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code ConcertCancelled} integration event.
 *
 * <p>See: docs/contracts/common/event-envelope.md §14.5
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConcertCancelledPayload {
    private UUID concertId;
    private Instant cancelledAt;
    private String reason;
}
