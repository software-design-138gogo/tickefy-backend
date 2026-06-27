package com.tickefy.order.modules.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminOrderResponse(
        UUID orderId,
        UUID userId,
        UUID concertId,
        String status,
        long totalAmount,
        String paymentUrl,
        Instant expiresAt,
        Instant createdAt,
        List<OrderItemResponse> items) {}
