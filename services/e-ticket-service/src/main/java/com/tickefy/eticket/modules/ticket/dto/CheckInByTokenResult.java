package com.tickefy.eticket.modules.ticket.dto;

public record CheckInByTokenResult(
        String result,
        String ticketId,
        String concertId,
        String zoneId,
        String zoneName,
        String holderName,
        String status
) {}
