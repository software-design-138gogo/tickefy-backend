package com.tickefy.eticket.modules.ticket.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketDto(
        UUID id,
        String orderId,
        String orderItemId,
        String userId,
        String eventId,
        String ticketTypeId,
        String zoneId,
        String ticketName,
        String status,
        String qrToken,
        Instant checkedInAt,
        Instant createdAt
) {}
