package com.tickefy.payment.modules.payment.dto;

public record CallbackRequest(
        String gatewayOrderId,
        String gatewayTransactionId,
        String status,
        Long amount,
        String signature) {}
