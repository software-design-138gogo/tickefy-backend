package com.tickefy.eticket.modules.ticket.dto;

import java.util.UUID;

public record TicketQrResponse(
        UUID ticketId,
        String qrToken,
        String qrTokenMasked
) {}
