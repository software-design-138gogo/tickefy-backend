package com.tickefy.inventory.modules.inventory.mapper;

import com.tickefy.inventory.modules.inventory.dto.TicketTypeResponse;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class TicketTypeMapper {

    /**
     * Compute status from sale window relative to now.
     * UPCOMING: now < saleStartAt
     * ON_SALE:  saleStartAt <= now <= saleEndAt
     * CLOSED:   now > saleEndAt
     */
    public String computeStatus(Instant saleStartAt, Instant saleEndAt) {
        Instant now = Instant.now();
        if (now.isBefore(saleStartAt)) return "UPCOMING";
        if (now.isAfter(saleEndAt)) return "CLOSED";
        return "ON_SALE";
    }

    public TicketTypeResponse toResponse(TicketTypeEntity entity, Integer available) {
        return new TicketTypeResponse(
                entity.getId(),
                entity.getConcertId(),
                entity.getName(),
                entity.getPrice(),
                entity.getPerUserLimit(),
                available,
                entity.getSaleStartAt(),
                entity.getSaleEndAt(),
                computeStatus(entity.getSaleStartAt(), entity.getSaleEndAt()));
    }
}
