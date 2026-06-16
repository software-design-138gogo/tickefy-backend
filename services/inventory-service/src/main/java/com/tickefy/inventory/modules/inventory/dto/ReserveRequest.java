package com.tickefy.inventory.modules.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReserveRequest(
        @NotNull UUID userId,
        @NotNull UUID ticketTypeId,
        @NotNull UUID orderId,
        @NotNull @Min(1) Integer quantity) {}
