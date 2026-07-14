package com.tickefy.order.modules.order.dto;

import java.util.UUID;

public record OrderItemResponse(
        UUID ticketTypeId,
        String ticketTypeName,
        int quantity,
        long unitPrice) {}
