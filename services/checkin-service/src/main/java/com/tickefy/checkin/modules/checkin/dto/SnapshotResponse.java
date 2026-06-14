package com.tickefy.checkin.modules.checkin.dto;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot DTO returned to mobile for offline scan preparation.
 */
public record SnapshotResponse(
        String concertId,
        String gate,
        Instant generatedAt,
        Instant expiresAt,
        int totalCount,
        List<SnapshotTicket> tickets
) {
    public record SnapshotTicket(
            String ticketId,
            String qrToken,
            String eventId,
            String zoneId,
            String zoneName,
            String holderName,
            String status
    ) {}
}
