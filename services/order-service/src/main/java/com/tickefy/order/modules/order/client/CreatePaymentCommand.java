package com.tickefy.order.modules.order.client;

import java.util.UUID;

public record CreatePaymentCommand(
        UUID orderId,
        UUID userId,
        long amount,
        String currency,
        String idempotencyKey) {}
