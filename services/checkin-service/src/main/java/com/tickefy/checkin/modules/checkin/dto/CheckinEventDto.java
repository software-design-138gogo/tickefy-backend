package com.tickefy.checkin.modules.checkin.dto;

import com.tickefy.checkin.modules.checkin.entity.CheckinEvent;
import java.time.Instant;
import java.util.UUID;

public record CheckinEventDto(
        UUID id,
        String ticketId,
        String qrTokenMasked,
        String concertId,
        String staffId,
        String deviceId,
        String gate,
        String result,
        boolean offline,
        Instant scannedAt,
        Instant syncedAt,
        String syncBatchId,
        String requestId
) {
    public static CheckinEventDto from(CheckinEvent event) {
        return new CheckinEventDto(
                event.getId(),
                event.getTicketId(),
                event.getQrTokenMasked(),
                event.getConcertId(),
                event.getStaffId(),
                event.getDeviceId(),
                event.getGate(),
                event.getResult(),
                event.isOffline(),
                event.getScannedAt(),
                event.getSyncedAt(),
                event.getSyncBatchId(),
                event.getRequestId());
    }
}
