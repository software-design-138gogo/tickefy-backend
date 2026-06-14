package com.tickefy.checkin.modules.checkin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

public record SyncRequest(
        @NotBlank String syncBatchId,
        @NotBlank String deviceId,
        @NotBlank String concertId,
        String gate,
        @NotEmpty List<SyncItem> items
) {
    public record SyncItem(
            String localId,
            @NotBlank String qrToken,
            String localResult,
            Instant scannedAt
    ) {}
}
