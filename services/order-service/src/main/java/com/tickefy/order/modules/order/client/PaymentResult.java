package com.tickefy.order.modules.order.client;

public record PaymentResult(
        String transactionId,
        String paymentUrl,
        String status) {}
