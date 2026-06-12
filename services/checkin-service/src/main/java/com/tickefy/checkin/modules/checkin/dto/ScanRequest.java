package com.tickefy.checkin.modules.checkin.dto;

import jakarta.validation.constraints.NotBlank;

public record ScanRequest(
        @NotBlank String qrToken,
        @NotBlank String concertId,
        @NotBlank String deviceId,
        String gate
) {}
