package com.tickefy.checkin.modules.checkin.dto;

import jakarta.validation.constraints.NotBlank;

public record ScanRequest(
        String qrToken,
        String qrTokenMasked,
        @NotBlank String concertId,
        @NotBlank String deviceId,
        String gate
) {
    public ScanRequest(String qrToken, String concertId, String deviceId, String gate) {
        this(qrToken, null, concertId, deviceId, gate);
    }

    public String scannedQrToken() {
        return qrToken != null && !qrToken.isBlank() ? qrToken : qrTokenMasked;
    }
}
