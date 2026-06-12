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
        List<SnapshotTokenEntry> tokens
) {
    public record SnapshotTokenEntry(
            String qrToken,
            String ticketId,
            String zone,
            String status
    ) {}
}
