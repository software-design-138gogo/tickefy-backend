package com.tickefy.order.modules.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull(message = "concertId is required") UUID concertId,
        @NotNull(message = "ticketTypeId is required") UUID ticketTypeId,
        @Min(value = 1, message = "quantity must be at least 1") int quantity,
        @NotBlank(message = "idempotencyKey is required") String idempotencyKey) {}
