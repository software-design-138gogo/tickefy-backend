package com.tickefy.inventory.modules.inventory.bootstrap;

import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeInventoryEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import com.tickefy.inventory.modules.inventory.service.InventoryRedisService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev-only seed logic for inventory. Creates 1 fixed concert + 5 fixed ticket-types so the FE
 * concert-detail page has a stable anchor (fixed concertId + ticketTypeId) showing real stock.
 *
 * <p>Idempotent: re-running does not duplicate rows nor break the oversell CHECK. New ticket-type =
 * insert + seed Redis to total. Existing ticket-type = re-sync Redis available = total-sold-reserved
 * (never reset to total, so existing reservations stay correct) + re-seed meta.
 *
 * <p>Kept in a separate bean (not the runner) so the {@code @Transactional} boundary is honoured via
 * the Spring proxy — a self-invoked transactional method on the runner would be ignored.
 */
@Component
public class DevSeedService {

    private static final Logger log = LoggerFactory.getLogger(DevSeedService.class);

    /** Fixed concert anchor. FE binds once, reuses forever. */
    static final UUID CONCERT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final int PER_USER_LIMIT = 4;

    /** Fixed ticket-type specs. Order = display order. Names exact (seat-map FE overlay matches by name). */
    static final List<SeedSpec> SPECS = List.of(
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000001"), "SVIP", 5_000_000, 50),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000002"), "VIP", 3_000_000, 100),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000003"), "CAT1", 1_500_000, 200),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000004"), "CAT2", 1_000_000, 300),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000005"), "GA", 500_000, 500));

    private final TicketTypeRepository ticketTypeRepository;
    private final TicketTypeInventoryRepository inventoryRepository;
    private final InventoryRedisService redisService;

    public DevSeedService(
            TicketTypeRepository ticketTypeRepository,
            TicketTypeInventoryRepository inventoryRepository,
            InventoryRedisService redisService) {
        this.ticketTypeRepository = ticketTypeRepository;
        this.inventoryRepository = inventoryRepository;
        this.redisService = redisService;
    }

    /** Seed result row for output table. */
    public record SeedResult(UUID ticketTypeId, String name, int price, int total, int available) {}

    /** Fixed ticket-type spec. */
    record SeedSpec(UUID id, String name, int price, int total) {}

    @Transactional
    public List<SeedResult> seedAll() {
        warnOnConflictingNames();

        // Sale window: opened yesterday, closes in a year -> ON_SALE.
        Instant now = Instant.now();
        Instant saleStartAt = now.minus(Duration.ofDays(1));
        Instant saleEndAt = now.plus(Duration.ofDays(365));

        return SPECS.stream()
                .map(spec -> seedOne(spec, saleStartAt, saleEndAt))
                .toList();
    }

    private SeedResult seedOne(SeedSpec spec, Instant saleStartAt, Instant saleEndAt) {
        if (ticketTypeRepository.existsById(spec.id())) {
            // Re-sync Redis to current DB state. Do NOT reset to total (would clobber live reservations).
            int available = inventoryRepository.findByTicketTypeId(spec.id())
                    .map(inv -> inv.getTotalQty() - inv.getSoldQty() - inv.getReservedQty())
                    .orElse(spec.total());
            redisService.setStock(spec.id(), available);
            redisService.seedMeta(spec.id(), PER_USER_LIMIT, spec.price(), saleStartAt, saleEndAt);
            log.info("Dev seed: ticket-type {} ({}) already exists, re-synced Redis available={}",
                    spec.id(), spec.name(), available);
            return new SeedResult(spec.id(), spec.name(), spec.price(), spec.total(), available);
        }

        TicketTypeEntity entity = TicketTypeEntity.builder()
                .id(spec.id()) // FIXED id — @PrePersist keeps it (only generates when null)
                .concertId(CONCERT_ID)
                .name(spec.name())
                .price(spec.price())
                .perUserLimit(PER_USER_LIMIT)
                .saleStartAt(saleStartAt)
                .saleEndAt(saleEndAt)
                .build();
        entity = ticketTypeRepository.save(entity);

        TicketTypeInventoryEntity inventory = TicketTypeInventoryEntity.builder()
                .ticketType(entity)
                .totalQty(spec.total())
                .soldQty(0)
                .reservedQty(0)
                .build();
        inventoryRepository.save(inventory);

        redisService.seedStock(spec.id(), spec.total());
        redisService.seedMeta(spec.id(), PER_USER_LIMIT, spec.price(), saleStartAt, saleEndAt);

        log.info("Dev seed: created ticket-type {} ({}) total={}", spec.id(), spec.name(), spec.total());
        return new SeedResult(spec.id(), spec.name(), spec.price(), spec.total(), spec.total());
    }

    /**
     * Warn (do NOT auto-delete) if a ticket-type with one of our seed names already exists under this
     * concert with a DIFFERENT id (leftover from old tests). FE matches name within a single concert,
     * so a stray duplicate name in THIS concert would confuse the overlay. Scoped to CONCERT_ID only.
     */
    private void warnOnConflictingNames() {
        Set<UUID> expectedIds = SPECS.stream().map(SeedSpec::id).collect(Collectors.toSet());
        Set<String> seedNames = SPECS.stream().map(SeedSpec::name).collect(Collectors.toSet());

        for (TicketTypeEntity e : ticketTypeRepository.findByConcertId(CONCERT_ID)) {
            if (seedNames.contains(e.getName()) && !expectedIds.contains(e.getId())) {
                log.warn("Dev seed CONFLICT: ticket-type name '{}' already exists under concert {} with "
                        + "different id {} (not the fixed seed id). Not auto-deleted — clean dev DB manually.",
                        e.getName(), CONCERT_ID, e.getId());
            }
        }
    }
}
