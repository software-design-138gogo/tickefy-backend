package com.tickefy.eticket.modules.ticket.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketDto(
        UUID id,
        String orderId,
        String orderItemId,
        String userId,
        String concertId,
        String ticketTypeId,
        String zoneId,
        String ticketTypeName,
        String status,
        String qrTokenMasked,
        Instant checkedInAt,
        Instant createdAt
) {}
