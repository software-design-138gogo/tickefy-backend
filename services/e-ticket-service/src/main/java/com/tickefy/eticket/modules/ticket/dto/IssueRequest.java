package com.tickefy.eticket.modules.ticket.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record IssueRequest(
        @NotBlank String orderId,
        @NotBlank String orderItemId,
        @NotBlank String userId,
        @NotBlank String concertId,
        String ticketTypeId,
        String zoneId,
        @JsonAlias("ticketName") String ticketTypeName
) {}
