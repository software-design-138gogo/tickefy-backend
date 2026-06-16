package com.tickefy.eticket.modules.ticket.dto;

import jakarta.validation.constraints.NotBlank;

public record IssueRequest(
        @NotBlank String orderId,
        @NotBlank String orderItemId,
        @NotBlank String userId,
        @NotBlank String concertId,
        String ticketTypeId,
        String zoneId,
        String ticketName
) {}
