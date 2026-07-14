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
 * Pure unit tests for DevSeedService (no Spring / Docker). Verifies the 5 core + 3 E2E helper
 * ticket-types are seeded with the correct per-type quota and per-user limit, and that re-running is
 * idempotent (existing ids re-sync, no re-create).
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class DevSeedServiceUnitTest {

    private static final UUID C1 = UUID.fromString("22222222-0000-4000-8000-0000000000a1");
    private static final UUID C5 = UUID.fromString("22222222-0000-4000-8000-0000000000a2");
    private static final UUID C2 = UUID.fromString("22222222-0000-4000-8000-0000000000a3");

    @Mock private TicketTypeRepository ticketTypeRepository;
    @Mock private TicketTypeInventoryRepository inventoryRepository;
    @Mock private InventoryRedisService redisService;

    private DevSeedService service;

    @BeforeEach
    void setUp() {
        service = new DevSeedService(ticketTypeRepository, inventoryRepository, redisService);
    }

    @Test
    void seedsFiveCorePlusThreeHelpers_withCorrectQuotaAndLimit() {
        when(ticketTypeRepository.existsById(any())).thenReturn(false);
        when(ticketTypeRepository.save(any(TicketTypeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.seedAll();

        ArgumentCaptor<TicketTypeEntity> ttCap = ArgumentCaptor.forClass(TicketTypeEntity.class);
        verify(ticketTypeRepository, times(8)).save(ttCap.capture());
        Map<String, TicketTypeEntity> byName = ttCap.getAllValues().stream()
                .collect(Collectors.toMap(TicketTypeEntity::getName, Function.identity()));

        assertThat(byName.keySet())
                .containsExactlyInAnyOrder(
                        "SVIP", "VIP", "CAT1", "CAT2", "GA", "LOWSTOCK-C1", "LOWSTOCK-C5", "LIMIT-C2");

        assertThat(byName.get("LOWSTOCK-C1").getId()).isEqualTo(C1);
        assertThat(byName.get("LOWSTOCK-C1").getPerUserLimit()).isEqualTo(2);
        assertThat(byName.get("LOWSTOCK-C5").getId()).isEqualTo(C5);
        assertThat(byName.get("LOWSTOCK-C5").getPerUserLimit()).isEqualTo(1);
        assertThat(byName.get("LIMIT-C2").getId()).isEqualTo(C2);
        assertThat(byName.get("LIMIT-C2").getPerUserLimit()).isEqualTo(2);

        ArgumentCaptor<TicketTypeInventoryEntity> invCap =
                ArgumentCaptor.forClass(TicketTypeInventoryEntity.class);
        verify(inventoryRepository, times(8)).save(invCap.capture());
        List<Integer> totals = invCap.getAllValues().stream()
                .map(TicketTypeInventoryEntity::getTotalQty)
                .toList();
        // helper totals present: C1=1, C5=1, C2=10
        assertThat(totals).contains(1, 1, 10);

        verify(redisService).seedStock(eq(C1), eq(1));
        verify(redisService).seedStock(eq(C5), eq(1));
        verify(redisService).seedStock(eq(C2), eq(10));
    }

    @Test
    void idempotent_existingTypesReSyncNotRecreate() {
        when(ticketTypeRepository.existsById(any())).thenReturn(true);
        when(inventoryRepository.findByTicketTypeId(any())).thenReturn(java.util.Optional.empty());

        service.seedAll();

        // Nothing re-created; Redis re-synced for all 8.
        verify(ticketTypeRepository, times(0)).save(any());
        verify(redisService, times(8)).setStock(any(), anyInt());
    }
}
