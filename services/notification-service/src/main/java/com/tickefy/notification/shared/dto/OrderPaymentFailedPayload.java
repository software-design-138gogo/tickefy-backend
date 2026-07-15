package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code OrderPaymentFailed} integration event.
 *
 * <p>See: docs/contracts/common/event-envelope.md §14.2.1
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderPaymentFailedPayload {
    private UUID orderId;
    private UUID userId;
    private UUID concertId;
    private UUID reservationId;
    private Instant failedAt;
    private String reason;
}
