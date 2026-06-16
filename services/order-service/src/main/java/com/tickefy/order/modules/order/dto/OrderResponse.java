package com.tickefy.order.modules.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String status,
        long totalAmount,
        String paymentUrl,
        Instant expiresAt,
        List<OrderItemResponse> items) {}
