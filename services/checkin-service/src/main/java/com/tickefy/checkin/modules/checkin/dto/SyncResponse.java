package com.tickefy.checkin.modules.checkin.dto;

import java.util.List;

public record SyncResponse(
        String syncBatchId,
        List<SyncItemResult> accepted,
        List<SyncItemResult> rejected,
        List<SyncItemResult> conflicts
) {
    public record SyncItemResult(
            String qrToken,
            String result,
            String ticketId
    ) {}
}
