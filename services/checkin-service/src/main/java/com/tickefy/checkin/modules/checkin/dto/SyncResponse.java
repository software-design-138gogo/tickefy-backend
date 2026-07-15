package com.tickefy.checkin.modules.checkin.dto;

import java.time.Instant;
import java.util.List;

public record SyncResponse(
        String syncBatchId,
        String result,
        String concertId,
        String deviceId,
        int totalItems,
        int acceptedCount,
        int rejectedCount,
        int conflictCount,
        boolean replayDetected,
        List<SyncItemResult> items
) {
    public record SyncItemResult(
            String offlineScanId,
            String ticketId,
            String result,
            Instant checkedInAt,
            Instant firstCheckedInAt,
            String conflictId
    ) {}
}
