package com.tickefy.order.modules.order.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record EnableRefundRequest(
        @NotNull(message = "concertId is required") UUID concertId) {}
