package com.tickefy.checkin.modules.checkin.dto;

import java.time.Instant;

public record ScanResponse(
        String result,      // ACCEPTED | DUPLICATE_REJECTED | INVALID_QR_TOKEN | WRONG_EVENT | CANCELLED_TICKET | REFUNDED_TICKET
        String ticketId,
        String concertId,
        String gate,
        Instant scannedAt
) {}
