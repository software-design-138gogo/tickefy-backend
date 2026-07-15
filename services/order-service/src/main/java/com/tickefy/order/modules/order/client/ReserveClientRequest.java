package com.tickefy.order.modules.order.client;

import java.util.UUID;

public record ReserveClientRequest(
        UUID userId,
        UUID ticketTypeId,
        UUID orderId,
        int quantity) {}
