package com.tickefy.inventory.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.inventory.modules.inventory.bootstrap.DevSeedService;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeInventoryEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import com.tickefy.inventory.modules.inventory.service.InventoryRedisService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests for DevSeedService (no Spring / Docker). Verifies the anchor 1111 (5 core + 3 E2E
 * helper) AND the 4 demo concerts (each 5 bare zones, fixed ids, low-stock template) are seeded with the
 * correct concert binding, quota and per-user limit, and that re-running is idempotent (existing ids
 * re-sync, no re-create).
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class DevSeedServiceUnitTest {

    private static final UUID ANCHOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID C1 = UUID.fromString("22222222-0000-4000-8000-0000000000a1");
    private static final UUID C5 = UUID.fromString("22222222-0000-4000-8000-0000000000a2");
    private static final UUID C2 = UUID.fromString("22222222-0000-4000-8000-0000000000a3");

    // 8 anchor + 4 demo × 5 + 2 sale-window × 5 = 38.
    private static final int TOTAL_SEEDED = 38;

    private static UUID demoConcert(int c) {
        return UUID.fromString(String.format("c1c1c1c1-0000-4000-8000-00000000000%d", c));
    }

    private static UUID demoTicket(int c, int z) {
        return UUID.fromString(String.format("dddd000%d-0000-4000-8000-00000000000%d", c, z));
    }

    @Mock private TicketTypeRepository ticketTypeRepository;
    @Mock private TicketTypeInventoryRepository inventoryRepository;
    @Mock private InventoryRedisService redisService;

    private DevSeedService service;

    @BeforeEach
    void setUp() {
        service = new DevSeedService(ticketTypeRepository, inventoryRepository, redisService);
    }

    @Test
    void seedsAnchorAndFourDemoConcerts_withFixedIdsConcertBindingQuotaAndLimit() {
        when(ticketTypeRepository.existsById(any())).thenReturn(false);
        when(ticketTypeRepository.save(any(TicketTypeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.seedAll();

        ArgumentCaptor<TicketTypeEntity> ttCap = ArgumentCaptor.forClass(TicketTypeEntity.class);
        verify(ticketTypeRepository, times(TOTAL_SEEDED)).save(ttCap.capture());
        Map<UUID, TicketTypeEntity> byId = ttCap.getAllValues().stream()
                .collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));

        // --- anchor 1111: 5 core + 3 helpers, all bound to ANCHOR ---
        List<String> anchorNames = byId.values().stream()
                .filter(e -> ANCHOR.equals(e.getConcertId()))
                .map(TicketTypeEntity::getName)
                .toList();
        assertThat(anchorNames)
                .containsExactlyInAnyOrder(
                        "SVIP", "VIP", "CAT1", "CAT2", "GA", "LOWSTOCK-C1", "LOWSTOCK-C5", "LIMIT-C2");
        assertThat(byId.get(C1).getPerUserLimit()).isEqualTo(2);
        assertThat(byId.get(C5).getPerUserLimit()).isEqualTo(1);
        assertThat(byId.get(C2).getPerUserLimit()).isEqualTo(2);

        // --- demo + sale-window concerts: 30 ticket-types (20 demo + 10 window), all c1c1c1c1 ---
        long c1c1Count = byId.values().stream()
                .filter(e -> e.getConcertId() != null
                        && e.getConcertId().toString().startsWith("c1c1c1c1-"))
                .count();
        assertThat(c1c1Count).isEqualTo(30);

        for (int c = 1; c <= 4; c++) {
            // SVIP (zone 1) → total-limit 1, GA (zone 5) → sold-out template, CAT2 (zone 4) → limit 2.
            TicketTypeEntity svip = byId.get(demoTicket(c, 1));
            assertThat(svip.getName()).isEqualTo("SVIP");
            assertThat(svip.getConcertId()).isEqualTo(demoConcert(c));
            assertThat(svip.getPerUserLimit()).isEqualTo(1);

            TicketTypeEntity cat2 = byId.get(demoTicket(c, 4));
            assertThat(cat2.getName()).isEqualTo("CAT2");
            assertThat(cat2.getPerUserLimit()).isEqualTo(2);

            TicketTypeEntity ga = byId.get(demoTicket(c, 5));
            assertThat(ga.getName()).isEqualTo("GA");
            assertThat(ga.getConcertId()).isEqualTo(demoConcert(c));
        }

        // --- 2 sale-window concerts (C3): each 5 zones, ticket-type window == concert window ---
        java.time.Instant c5Start = java.time.Instant.parse("2026-08-01T00:00:00Z");
        java.time.Instant c5End = java.time.Instant.parse("2027-06-30T00:00:00Z");
        java.time.Instant c6Start = java.time.Instant.parse("2026-06-01T00:00:00Z");
        java.time.Instant c6End = java.time.Instant.parse("2026-06-30T00:00:00Z");
        for (int z = 1; z <= 5; z++) {
            TicketTypeEntity t5 = byId.get(demoTicket(5, z));
            assertThat(t5.getConcertId()).isEqualTo(demoConcert(5));
            assertThat(t5.getSaleStartAt()).isEqualTo(c5Start);
            assertThat(t5.getSaleEndAt()).isEqualTo(c5End);

            TicketTypeEntity t6 = byId.get(demoTicket(6, z));
            assertThat(t6.getConcertId()).isEqualTo(demoConcert(6));
            assertThat(t6.getSaleStartAt()).isEqualTo(c6Start);
            assertThat(t6.getSaleEndAt()).isEqualTo(c6End);
        }

        // --- inventory totals: anchor helpers (1,1,10) + every demo GA/SVIP total=1 present ---
        ArgumentCaptor<TicketTypeInventoryEntity> invCap =
                ArgumentCaptor.forClass(TicketTypeInventoryEntity.class);
        verify(inventoryRepository, times(TOTAL_SEEDED)).save(invCap.capture());
        List<Integer> totals = invCap.getAllValues().stream()
                .map(TicketTypeInventoryEntity::getTotalQty)
                .toList();
        assertThat(totals).contains(1, 1, 10);

        // --- Redis stock seeded for sold-out demo zones (GA total=1) each concert ---
        verify(redisService).seedStock(eq(demoTicket(1, 5)), eq(1)); // concert1 GA
        verify(redisService).seedStock(eq(demoTicket(4, 5)), eq(1)); // concert4 GA
        verify(redisService).seedStock(eq(demoTicket(2, 4)), eq(10)); // concert2 CAT2 total=10
    }

    @Test
    void idempotent_existingTypesReSyncNotRecreate() {
        when(ticketTypeRepository.existsById(any())).thenReturn(true);
        when(inventoryRepository.findByTicketTypeId(any())).thenReturn(java.util.Optional.empty());

        service.seedAll();

        // Nothing re-created; Redis re-synced for all 28.
        verify(ticketTypeRepository, times(0)).save(any());
        verify(redisService, times(TOTAL_SEEDED)).setStock(any(), anyInt());
    }
}
