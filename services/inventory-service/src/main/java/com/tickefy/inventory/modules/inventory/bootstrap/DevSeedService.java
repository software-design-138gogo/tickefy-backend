package com.tickefy.inventory.modules.inventory.bootstrap;

import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeInventoryEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import com.tickefy.inventory.modules.inventory.service.InventoryRedisService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev-only seed logic for inventory. Creates the fixed-UUID ticket-types backing every seeded concert so
 * the FE booking flow has real stock: the rehearsal anchor {@code 1111} (many tickets — 5 core + 3 E2E
 * helpers) plus the 4 demo concerts (each 5 bare zones, LOW stock for the group-C scenarios). Concert +
 * zone rows live in event-service (EventAnchorSeeder); ticket-type names here match those zone names
 * exactly (§6.10).
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

    /** Fixed rehearsal anchor concert. FE binds once, reuses forever. Must equal EventAnchorSeeder id. */
    static final UUID CONCERT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final int PER_USER_LIMIT = 4;

    /** Fixed ticket-type specs for the anchor 1111. Order = display order. Names exact (seat-map match). */
    static final List<SeedSpec> SPECS = List.of(
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000001"), "SVIP", 5_000_000, 50, PER_USER_LIMIT),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000002"), "VIP", 3_000_000, 100, PER_USER_LIMIT),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000003"), "CAT1", 1_500_000, 200, PER_USER_LIMIT),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000004"), "CAT2", 1_000_000, 300, PER_USER_LIMIT),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-000000000005"), "GA", 500_000, 500, PER_USER_LIMIT));

    /**
     * E2E helper ticket-types on the SAME anchor concert. Low/limited stock so the FE E2E can exercise
     * SOLD_OUT (C1), concurrent double-buy (C5), and per-user-limit (C2). Distinct names so they never
     * clash with the 5 core seed names. NOTE: {@code total=1} types are consumed by a run — they only
     * re-appear fresh after a volume reset (idempotent existsById does NOT top-up). Document, no auto-replenish.
     */
    static final List<SeedSpec> HELPER_SPECS = List.of(
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-0000000000a1"), "LOWSTOCK-C1", 500_000, 1, 2),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-0000000000a2"), "LOWSTOCK-C5", 500_000, 1, 1),
            new SeedSpec(UUID.fromString("22222222-0000-4000-8000-0000000000a3"), "LIMIT-C2", 500_000, 10, 2));

    /** Fixed demo concerts (match EventAnchorSeeder c1c1c1c1-…-0001..0004). Each gets the 5 zones below. */
    static final List<UUID> DEMO_CONCERT_IDS = List.of(
            UUID.fromString("c1c1c1c1-0000-4000-8000-000000000001"),
            UUID.fromString("c1c1c1c1-0000-4000-8000-000000000002"),
            UUID.fromString("c1c1c1c1-0000-4000-8000-000000000003"),
            UUID.fromString("c1c1c1c1-0000-4000-8000-000000000004"));

    /**
     * Low-stock template shared by ALL 4 demo concerts. zoneIndex 1..5 → ticket-type id
     * {@code dddd000<C>-0000-4000-8000-00000000000<Z>} (C = demo concert 1..4). Tuned for group-C:
     * GA total=1 (C1 sold-out), SVIP total=1 (C5 concurrent), CAT2 perUserLimit=2 (C2 per-user-limit).
     */
    static final List<DemoZone> DEMO_TEMPLATE = List.of(
            new DemoZone(1, "SVIP", 3_500_000, 1, 1),
            new DemoZone(2, "VIP", 2_300_000, 2, 4),
            new DemoZone(3, "CAT1", 1_600_000, 2, 4),
            new DemoZone(4, "CAT2", 980_000, 10, 2),
            new DemoZone(5, "GA", 650_000, 1, 2));

    /** Demo-concert zone template row (zoneIndex feeds the fixed ticket-type UUID). */
    record DemoZone(int zoneIndex, String name, int price, int total, int perUserLimit) {}

    /**
     * Sale-window test concerts (C3, match EventAnchorSeeder c1c1c1c1-…-0005/0006). Full stock so the ONLY
     * reserve blocker is the sale window — the ticket-type saleStartAt/saleEndAt below MUST equal the
     * concert window (that is where inventory reserve enforces SALE_WINDOW_CLOSED). Absolute times.
     */
    static final List<SaleWindowConcert> SALE_WINDOW_CONCERTS = List.of(
            new SaleWindowConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000005"), 5,
                    Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2027-06-30T00:00:00Z")),
            new SaleWindowConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000006"), 6,
                    Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-30T00:00:00Z")));

    /** Full-stock 5-zone template for the sale-window concerts (total=20, limit=4 — never the blocker). */
    static final List<DemoZone> SALE_WINDOW_TEMPLATE = List.of(
            new DemoZone(1, "SVIP", 3_500_000, 20, 4),
            new DemoZone(2, "VIP", 2_300_000, 20, 4),
            new DemoZone(3, "CAT1", 1_600_000, 20, 4),
            new DemoZone(4, "CAT2", 980_000, 20, 4),
            new DemoZone(5, "GA", 650_000, 20, 4));

    /** Sale-window test concert (fixed id, concertNo feeds the ticket-type UUID, absolute window). */
    record SaleWindowConcert(UUID concertId, int concertNo, Instant saleStart, Instant saleEnd) {}

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
    public record SeedResult(UUID concertId, UUID ticketTypeId, String name, int price, int total, int available) {}

    /** Fixed ticket-type spec. */
    record SeedSpec(UUID id, String name, int price, int total, int perUserLimit) {}

    @Transactional
    public List<SeedResult> seedAll() {
        warnOnConflictingNames();

        // Sale window: opened yesterday, closes in a year -> ON_SALE.
        Instant now = Instant.now();
        Instant saleStartAt = now.minus(Duration.ofDays(1));
        Instant saleEndAt = now.plus(Duration.ofDays(365));

        List<SeedResult> results = new ArrayList<>();

        // Anchor 1111 — UNCHANGED: 5 core + 3 helper.
        for (SeedSpec spec :
                java.util.stream.Stream.concat(SPECS.stream(), HELPER_SPECS.stream()).toList()) {
            results.add(seedOne(CONCERT_ID, spec, saleStartAt, saleEndAt));
        }

        // 4 demo concerts — each the 5-zone low-stock template.
        for (int c = 0; c < DEMO_CONCERT_IDS.size(); c++) {
            UUID demoConcertId = DEMO_CONCERT_IDS.get(c);
            int concertNo = c + 1;
            for (DemoZone z : DEMO_TEMPLATE) {
                SeedSpec spec = new SeedSpec(demoTicketTypeId(concertNo, z.zoneIndex()),
                        z.name(), z.price(), z.total(), z.perUserLimit());
                results.add(seedOne(demoConcertId, spec, saleStartAt, saleEndAt));
            }
        }

        // 2 sale-window test concerts (C3) — full stock, ticket-type window = concert window (deterministic).
        for (SaleWindowConcert sw : SALE_WINDOW_CONCERTS) {
            for (DemoZone z : SALE_WINDOW_TEMPLATE) {
                SeedSpec spec = new SeedSpec(demoTicketTypeId(sw.concertNo(), z.zoneIndex()),
                        z.name(), z.price(), z.total(), z.perUserLimit());
                results.add(seedOne(sw.concertId(), spec, sw.saleStart(), sw.saleEnd()));
            }
        }

        return results;
    }

    /** Fixed ticket-type id for a demo concert zone: {@code dddd000<C>-0000-4000-8000-00000000000<Z>}. */
    private static UUID demoTicketTypeId(int concertNo, int zoneIndex) {
        return UUID.fromString(
                String.format("dddd000%d-0000-4000-8000-00000000000%d", concertNo, zoneIndex));
    }

    private SeedResult seedOne(UUID concertId, SeedSpec spec, Instant saleStartAt, Instant saleEndAt) {
        if (ticketTypeRepository.existsById(spec.id())) {
            // Re-sync Redis to current DB state. Do NOT reset to total (would clobber live reservations).
            int available = inventoryRepository.findByTicketTypeId(spec.id())
                    .map(inv -> inv.getTotalQty() - inv.getSoldQty() - inv.getReservedQty())
                    .orElse(spec.total());
            redisService.setStock(spec.id(), available);
            redisService.seedMeta(spec.id(), spec.perUserLimit(), spec.price(), saleStartAt, saleEndAt);
            log.info("Dev seed: ticket-type {} ({}) already exists, re-synced Redis available={}",
                    spec.id(), spec.name(), available);
            return new SeedResult(concertId, spec.id(), spec.name(), spec.price(), spec.total(), available);
        }

        TicketTypeEntity entity = TicketTypeEntity.builder()
                .id(spec.id()) // FIXED id — @PrePersist keeps it (only generates when null)
                .concertId(concertId)
                .name(spec.name())
                .price(spec.price())
                .perUserLimit(spec.perUserLimit())
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
        redisService.seedMeta(spec.id(), spec.perUserLimit(), spec.price(), saleStartAt, saleEndAt);

        log.info("Dev seed: created ticket-type {} ({}) concert={} total={}",
                spec.id(), spec.name(), concertId, spec.total());
        return new SeedResult(concertId, spec.id(), spec.name(), spec.price(), spec.total(), spec.total());
    }

    /**
     * Warn (do NOT auto-delete) if a ticket-type with one of our anchor seed names already exists under the
     * anchor concert with a DIFFERENT id (leftover from old tests). FE matches name within a single concert,
     * so a stray duplicate name would confuse the overlay. Scoped to the anchor CONCERT_ID only.
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
