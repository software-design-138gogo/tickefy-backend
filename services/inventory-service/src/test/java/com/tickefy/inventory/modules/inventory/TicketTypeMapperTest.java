package com.tickefy.inventory.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.inventory.modules.inventory.dto.TicketTypeResponse;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.mapper.TicketTypeMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies TicketTypeMapper now exposes the Postgres quantity counters (total/sold/reserved) the admin
 * dashboard needs, while keeping every legacy field (incl. the Redis-sourced {@code available}) intact.
 */
@Tag("unit")
class TicketTypeMapperTest {

    private final TicketTypeMapper mapper = new TicketTypeMapper();

    @Test
    void toResponse_exposesTotalSoldReserved_keepingAvailableAndLegacyFields() {
        UUID id = UUID.fromString("dddd0001-0000-4000-8000-000000000004");
        UUID concertId = UUID.fromString("c1c1c1c1-0000-4000-8000-000000000001");
        Instant start = Instant.now().minusSeconds(3600);
        Instant end = Instant.now().plusSeconds(3600);

        TicketTypeEntity entity = TicketTypeEntity.builder()
                .id(id)
                .concertId(concertId)
                .name("CAT2")
                .price(980_000)
                .perUserLimit(2)
                .saleStartAt(start)
                .saleEndAt(end)
                .build();

        // Real CAT2 anchor: total=10, sold=2, reserved=0 -> available=8 (Redis).
        TicketTypeResponse res = mapper.toResponse(entity, 8, 10, 2, 0);

        // NEW quantity fields.
        assertThat(res.total()).isEqualTo(10);
        assertThat(res.sold()).isEqualTo(2);
        assertThat(res.reserved()).isEqualTo(0);

        // Legacy fields unchanged (apps/web must not break).
        assertThat(res.available()).isEqualTo(8);
        assertThat(res.id()).isEqualTo(id);
        assertThat(res.concertId()).isEqualTo(concertId);
        assertThat(res.name()).isEqualTo("CAT2");
        assertThat(res.price()).isEqualTo(980_000);
        assertThat(res.perUserLimit()).isEqualTo(2);
        assertThat(res.saleStartAt()).isEqualTo(start);
        assertThat(res.saleEndAt()).isEqualTo(end);
        assertThat(res.status()).isEqualTo("ON_SALE");

        // Sanity: available == total - sold - reserved for a settled row.
        assertThat(res.available()).isEqualTo(res.total() - res.sold() - res.reserved());
    }
}
