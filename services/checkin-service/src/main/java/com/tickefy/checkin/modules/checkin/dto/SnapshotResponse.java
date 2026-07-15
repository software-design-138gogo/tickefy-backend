package com.tickefy.checkin.modules.checkin.dto;

import com.tickefy.checkin.modules.vip.dto.VipGuestSnapshotDto;
import java.time.Instant;
import java.util.List;

/**
 * Snapshot DTO returned to mobile for offline scan preparation.
 */
public record SnapshotResponse(
        String snapshotId,
        String concertId,
        String gate,
        int version,
        Instant generatedAt,
        Instant expiresAt,
        int totalCount,
        List<SnapshotTicket> tickets,
        List<VipGuestSnapshotDto> vipGuests
) {
    public record SnapshotTicket(
            String ticketId,
            String qrTokenMasked,
            String qrTokenHash,
            String concertId,
            String zoneId,
            String zoneName,
            String holderName,
            String status
    ) {}
}
