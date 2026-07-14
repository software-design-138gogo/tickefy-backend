package com.tickefy.event.database;

import com.tickefy.event.modules.concert.Concert;
import com.tickefy.event.modules.concert.ConcertRepository;
import com.tickefy.event.modules.concert.ConcertZone;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev-only E2E concert seeder. Creates the FIXED-UUID, inventory-backed concert catalog so that after
 * a volume reset every concert is buyable: bare zone names (SVIP/VIP/CAT1/CAT2/GA — matching
 * inventory-service ticket-type names, §6.10) and fixed concert/venue ids the FE and inventory bind to.
 *
 * <p>Seeds:
 * <ul>
 *   <li>the rehearsal anchor {@code 11111111-1111-4111-8111-111111111111} (many tickets — the anchor
 *       inventory-service seeds 5 core + 3 helper ticket-types for);</li>
 *   <li>4 demo concerts ({@code c1c1c1c1-…-0001..0004}) each with the SAME 5 bare zones and LOW-stock
 *       inventory (seeded by inventory DevSeedService) for the group-C E2E scenarios.</li>
 * </ul>
 *
 * <p>This REPLACES the former demo-concert block in {@link DatabaseSeeder} (which used random UUIDs and
 * a {@code "Vé "}-prefixed ticketTypeName that broke cross-service name-resolution). DatabaseSeeder now
 * seeds artists only.
 *
 * <p>Concert ids are forced via a native INSERT because {@link Concert} uses
 * {@code @GeneratedValue(strategy=UUID)} (Hibernate always generates, no setter). Zones are persisted
 * via JPA (their id is free — resolved by name). Timestamps are set explicitly because the native
 * INSERT bypasses {@code @PrePersist}. Each concert/venue is guarded by {@code existsById} → idempotent.
 *
 * <p>Gated by {@code app.dev.seed.enabled=true} (default false), NOT by Spring profile: both dev and the
 * prod image run {@code SPRING_PROFILES_ACTIVE=docker}. The flag is set only in
 * {@code docker-compose.dev.yml} and {@code application-dev.yml}; the prod image leaves it unset.
 */
@Component
@ConditionalOnProperty(name = "app.dev.seed.enabled", havingValue = "true")
public class EventAnchorSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventAnchorSeeder.class);

    /** Fixed anchor concert — must equal inventory DevSeedService.CONCERT_ID. */
    static final UUID ANCHOR_CONCERT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** Fixed anchor venue (self-contained; DatabaseSeeder venues use random ids). */
    static final UUID ANCHOR_VENUE_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

    /** Fixed demo venues (own the names formerly created with random ids in DatabaseSeeder). */
    static final UUID VENUE_QK7 = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
    static final UUID VENUE_MY_DINH = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000003");

    /** Bare ticket-type names — MUST match inventory ticket_types.name (§6.10). No "Vé " prefix. */
    static final List<String[]> ZONES = List.of(
            new String[] {"SVIP", "SVIP Zone"},
            new String[] {"VIP", "VIP Zone"},
            new String[] {"CAT1", "Category 1"},
            new String[] {"CAT2", "Category 2"},
            new String[] {"GA", "General Admission"});

    /** Fixed demo concerts — same 5 bare zones each, low-stock inventory seeded by inventory-service. */
    static final List<DemoConcert> DEMO_CONCERTS = List.of(
            new DemoConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000001"),
                    "Anh Trai Say Hi Concert 2026",
                    "Concert quy tụ dàn anh trai cực phẩm.",
                    VENUE_QK7,
                    30),
            new DemoConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000002"),
                    "Anh Trai Vượt Chông Gai 2026",
                    "Đêm nhạc bùng nổ của các anh tài.",
                    VENUE_MY_DINH,
                    45),
            new DemoConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000003"),
                    "Em Xinh Say Hi",
                    "Đêm diễn tràn ngập sự ngọt ngào.",
                    VENUE_QK7,
                    50),
            new DemoConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000004"),
                    "Chị Đẹp Đạp Gió Rẽ Sóng",
                    "Sự kết hợp hoàn hảo của các chị đẹp.",
                    VENUE_MY_DINH,
                    60));

    /** Demo concert spec (fixed id, title, description, venue, eventDate offset in days from now). */
    record DemoConcert(UUID id, String title, String description, UUID venueId, int eventDaysAhead) {}

    /**
     * Sale-window test concerts (C3 SALE_WINDOW_CLOSED). PUBLISHED with full stock so the ONLY blocker is
     * the sale window: 0005 has a FUTURE saleStart (not open yet), 0006 has a PAST saleEnd (already
     * closed). Windows are ABSOLUTE (not now-relative) so open/closed stays deterministic across runs.
     */
    static final List<SaleWindowConcert> SALE_WINDOW_CONCERTS = List.of(
            new SaleWindowConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000005"),
                    "E2E Sale-NotStarted Concert",
                    Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2027-06-30T00:00:00Z"),
                    Instant.parse("2026-09-15T12:00:00Z")),
            new SaleWindowConcert(
                    UUID.fromString("c1c1c1c1-0000-4000-8000-000000000006"),
                    "E2E Sale-Closed Concert",
                    Instant.parse("2026-06-01T00:00:00Z"),
                    Instant.parse("2026-06-30T00:00:00Z"),
                    Instant.parse("2026-09-01T12:00:00Z")));

    /** Sale-window test concert with absolute sale window + event date. Reuses venue {@link #VENUE_QK7}. */
    record SaleWindowConcert(UUID id, String title, Instant saleStart, Instant saleEnd, Instant eventDate) {}

    private final EntityManager em;
    private final ConcertRepository concertRepository;

    public EventAnchorSeeder(EntityManager em, ConcertRepository concertRepository) {
        this.em = em;
        this.concertRepository = concertRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        OffsetDateTime saleStart = now.minusDays(1);

        // Fixed venues (guarded — the anchor venue keeps its original data unchanged).
        insertVenueIfMissing(
                ANCHOR_VENUE_ID, "Tickefy Rehearsal Arena", "1 Rehearsal Way", "TP. Hồ Chí Minh", 25000, now);
        insertVenueIfMissing(
                VENUE_QK7, "Sân vận động Quân Khu 7", "202 Hoàng Văn Thụ", "TP. Hồ Chí Minh", 25000, now);
        insertVenueIfMissing(
                VENUE_MY_DINH, "Sân vận động Quốc gia Mỹ Đình", "Đường Lê Đức Thọ", "Hà Nội", 40000, now);

        // Rehearsal anchor 1111 — DATA UNCHANGED (fixed saleEnd, eventDate now+30d, 5 bare zones).
        OffsetDateTime anchorSaleEnd =
                OffsetDateTime.ofInstant(Instant.parse("2027-06-23T00:00:00Z"), ZoneOffset.UTC);
        OffsetDateTime anchorEventDate =
                OffsetDateTime.ofInstant(Instant.now().plus(30, ChronoUnit.DAYS), ZoneOffset.UTC);
        insertConcertWithZonesIfMissing(
                ANCHOR_CONCERT_ID,
                "Tickefy Rehearsal Concert",
                "E2E baseline anchor concert (dev seed).",
                saleStart,
                anchorSaleEnd,
                anchorEventDate,
                ANCHOR_VENUE_ID,
                now);

        // 4 demo concerts — same 5 bare zones, sale open now, event 30..60 days ahead.
        OffsetDateTime demoSaleEnd = now.plusDays(365);
        for (DemoConcert dc : DEMO_CONCERTS) {
            OffsetDateTime eventDate = now.plusDays(dc.eventDaysAhead());
            insertConcertWithZonesIfMissing(
                    dc.id(), dc.title(), dc.description(), saleStart, demoSaleEnd, eventDate, dc.venueId(), now);
        }

        // 2 sale-window test concerts (C3) — full stock, only blocker is the window. Absolute times.
        for (SaleWindowConcert sw : SALE_WINDOW_CONCERTS) {
            insertConcertWithZonesIfMissing(
                    sw.id(),
                    sw.title(),
                    "E2E sale-window test concert (dev seed).",
                    OffsetDateTime.ofInstant(sw.saleStart(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(sw.saleEnd(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(sw.eventDate(), ZoneOffset.UTC),
                    VENUE_QK7,
                    now);
        }
    }

    /**
     * Native INSERT one concert with a forced id ({@code @GeneratedValue} would otherwise randomise it),
     * plus its 5 bare-name zones via JPA. Idempotent: skips when the concert already exists.
     */
    private void insertConcertWithZonesIfMissing(
            UUID concertId,
            String title,
            String description,
            OffsetDateTime saleStart,
            OffsetDateTime saleEnd,
            OffsetDateTime eventDate,
            UUID venueId,
            OffsetDateTime now) {
        if (concertRepository.existsById(concertId)) {
            log.info("Event concert {} already exists — skipping seed", concertId);
            return;
        }

        em.createNativeQuery(
                        "INSERT INTO concerts "
                                + "(id, title, description, status, sale_start_at, sale_end_at, "
                                + "event_date, venue_id, reminder_sent, created_at, updated_at) "
                                + "VALUES (:id, :title, :description, :status, :saleStart, :saleEnd, "
                                + ":eventDate, :venueId, :reminderSent, :createdAt, :updatedAt)")
                .setParameter("id", concertId)
                .setParameter("title", title)
                .setParameter("description", description)
                .setParameter("status", "PUBLISHED")
                .setParameter("saleStart", saleStart)
                .setParameter("saleEnd", saleEnd)
                .setParameter("eventDate", eventDate)
                .setParameter("venueId", venueId)
                .setParameter("reminderSent", false)
                .setParameter("createdAt", now)
                .setParameter("updatedAt", now)
                .executeUpdate();
        em.flush();

        Concert concertRef = em.getReference(Concert.class, concertId);
        for (String[] z : ZONES) {
            ConcertZone zone = new ConcertZone();
            zone.setConcert(concertRef);
            zone.setTicketTypeName(z[0]); // BARE name
            zone.setZoneName(z[1]);
            em.persist(zone);
        }
        em.flush();

        log.info(
                "Event concert seeded: {} \"{}\" PUBLISHED + {} zones (bare names)",
                concertId,
                title,
                ZONES.size());
    }

    private void insertVenueIfMissing(
            UUID id, String name, String address, String city, int capacity, OffsetDateTime now) {
        Long count = ((Number) em.createNativeQuery("SELECT count(*) FROM venues WHERE id = :id")
                        .setParameter("id", id)
                        .getSingleResult())
                .longValue();
        if (count > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO venues (id, name, address, city, capacity, created_at, updated_at) "
                                + "VALUES (:id, :name, :address, :city, :capacity, :createdAt, :updatedAt)")
                .setParameter("id", id)
                .setParameter("name", name)
                .setParameter("address", address)
                .setParameter("city", city)
                .setParameter("capacity", capacity)
                .setParameter("createdAt", now)
                .setParameter("updatedAt", now)
                .executeUpdate();
        em.flush();
    }
}
