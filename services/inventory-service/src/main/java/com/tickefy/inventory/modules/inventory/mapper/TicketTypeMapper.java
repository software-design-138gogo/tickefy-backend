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

    /**
     * @param available live count from Redis (unchanged source of truth for realtime stock)
     * @param total/sold/reserved Postgres counters from ticket_type_inventory (may be null if the
     *     inventory row is missing). NOTE mixed source: available=Redis, total/sold/reserved=Postgres —
     *     transient drift is possible (see REPORT). sold_qty is authoritative for "đã bán".
     */
    public TicketTypeResponse toResponse(
            TicketTypeEntity entity, Integer available, Integer total, Integer sold, Integer reserved) {
        return new TicketTypeResponse(
                entity.getId(),
                entity.getConcertId(),
                entity.getName(),
                entity.getPrice(),
                entity.getPerUserLimit(),
                available,
                total,
                sold,
                reserved,
                entity.getSaleStartAt(),
                entity.getSaleEndAt(),
                computeStatus(entity.getSaleStartAt(), entity.getSaleEndAt()));
    }
}
