package com.tickefy.checkin.modules.checkin.dto;

import java.util.List;

public record SyncResponse(
        String syncBatchId,
        int processed,
        List<SyncItemResult> accepted,
        List<SyncItemResult> rejected,
        List<SyncItemResult> conflicts
) {
    public record SyncItemResult(
            String localId,
            String qrToken,
            String serverResult,
            String ticketId
    ) {}
}
