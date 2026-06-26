package com.tickefy.checkin.modules.checkin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

public record SyncRequest(
        @NotBlank String syncBatchId,
        String snapshotId,
        @NotBlank String deviceId,
        @NotBlank String concertId,
        String gate,
        @NotEmpty List<SyncItem> items
) {
    public SyncRequest(String syncBatchId, String deviceId, String concertId, String gate, List<SyncItem> items) {
        this(syncBatchId, null, deviceId, concertId, gate, items);
    }

    public record SyncItem(
            String localId,
            String offlineScanId,
            String ticketId,
            String qrToken,
            String qrTokenMasked,
            String localResult,
            Instant scannedAt
    ) {
        public SyncItem(String localId, String qrToken, String localResult, Instant scannedAt) {
            this(localId, null, null, qrToken, null, localResult, scannedAt);
        }

        public String stableScanId() {
            return offlineScanId != null && !offlineScanId.isBlank() ? offlineScanId : localId;
        }

        public String scannedQrToken() {
            return qrToken != null && !qrToken.isBlank() ? qrToken : qrTokenMasked;
        }
    }
}
