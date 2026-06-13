package com.tickefy.inventory.modules.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

public record CreateTicketTypeRequest(
        @NotBlank String name,
        @NotNull @PositiveOrZero Integer price,
        @NotNull @Positive Integer totalQty,
        @Positive Integer perUserLimit,
        @NotNull Instant saleStartAt,
        @NotNull Instant saleEndAt) {}
