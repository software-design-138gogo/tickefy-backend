package com.tickefy.order.modules.order.client;

import java.time.Instant;
import java.util.UUID;

public record ReservationResult(
        UUID reservationId,
        long unitPrice,
        long totalAmount,
        Instant expiresAt,
        String ticketTypeName) {}
