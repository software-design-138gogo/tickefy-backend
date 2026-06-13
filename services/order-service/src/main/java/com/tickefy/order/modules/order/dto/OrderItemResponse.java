package com.tickefy.order.modules.order.dto;

import java.util.UUID;

public record OrderItemResponse(
        UUID ticketTypeId,
        int quantity,
        long unitPrice) {}
