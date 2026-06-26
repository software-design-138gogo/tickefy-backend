package com.tickefy.checkin.modules.checkin.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSnapshotRequest(
        @NotBlank String concertId,
        @NotBlank String deviceId,
        String gate
) {}
