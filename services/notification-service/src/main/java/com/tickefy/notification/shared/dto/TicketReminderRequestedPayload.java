package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload for the {@code TicketReminderRequested} integration event. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketReminderRequestedPayload {
    private UUID userId;
    private UUID concertId;
    private String concertTitle;
    private Instant eventDateTime;
    private Integer ticketCount;
}
