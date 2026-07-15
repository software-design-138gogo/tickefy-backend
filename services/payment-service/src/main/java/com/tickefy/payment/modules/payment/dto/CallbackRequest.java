package com.tickefy.payment.modules.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CallbackRequest(
        @NotBlank String gatewayOrderId,
        @NotBlank String gatewayTransactionId,
        @NotBlank String status,
        @NotNull @Positive Long amount,
        String signature) {}
