package com.tickefy.eticket.modules.ticket.dto;

import java.util.UUID;

public record CheckInResult(
        String result,   // ACCEPTED | DUPLICATE_REJECTED
        UUID ticketId
) {}
