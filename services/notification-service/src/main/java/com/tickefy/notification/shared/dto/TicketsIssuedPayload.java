package com.tickefy.notification.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code TicketsIssued} integration event.
 *
 * <p>See: docs/contracts/common/event-envelope.md §14.3
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketsIssuedPayload {

    private UUID orderId;

    private UUID userId;

    private UUID concertId;

    private List<TicketPayload> tickets;

    private Instant issuedAt;

    /** Nested DTO for each issued ticket. */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketPayload {

        private UUID ticketId;

        private UUID orderItemId;

        private UUID ticketTypeId;

        private String ticketTypeName;

        /** Ticket status (e.g. "ISSUED"). */
        private String status;
    }
}
