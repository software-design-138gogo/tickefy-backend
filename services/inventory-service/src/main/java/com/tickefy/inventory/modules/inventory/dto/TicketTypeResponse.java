package com.tickefy.inventory.modules.inventory.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketTypeResponse(
        UUID id,
        UUID concertId,
        String name,
        Integer price,
        Integer perUserLimit,
        Integer available,
        Integer total,
        Integer sold,
        Integer reserved,
        Instant saleStartAt,
        Instant saleEndAt,
        String status) {}
