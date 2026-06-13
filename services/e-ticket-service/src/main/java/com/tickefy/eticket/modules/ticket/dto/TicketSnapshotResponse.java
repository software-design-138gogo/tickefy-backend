package com.tickefy.eticket.modules.ticket.dto;

import java.time.Instant;
import java.util.List;

public record TicketSnapshotResponse(
        String concertId,
        Instant generatedAt,
        int totalCount,
        List<TicketSnapshotItem> tickets
) {
    public record TicketSnapshotItem(
            String ticketId,
            String qrToken,
            String eventId,
            String zoneId,
            String zoneName,
            String holderName,
            String status,
            Instant updatedAt
    ) {}
}
