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
 * Dev-only anchor seeder for the FE E2E baseline. Creates ONE fixed concert
 * ({@code 11111111-1111-4111-8111-111111111111}) plus a fixed venue and 5 zones whose
 * {@code ticketTypeName} are the BARE names (SVIP/VIP/CAT1/CAT2/GA) — matching inventory-service
 * ticket-type names exactly so cross-service name-resolution (§6.10) resolves. This is the anchor
 * inventory-service already seeds ticket-types for; without it the concert is orphaned in inventory.
 *
 * <p>SEPARATE from {@link DatabaseSeeder} (Dương's demo catalog) on purpose:
 * <ul>
 *   <li>DatabaseSeeder short-circuits on {@code artistRepository.count()>0}; this anchor must seed
 *       regardless, so it carries its OWN guard {@code concertRepository.existsById(ANCHOR)}.</li>
 *   <li>The 4 demo concerts and the {@code "Vé "+name} zone convention are left untouched.</li>
 * </ul>
 *
 * <p>The concert id is forced via a native INSERT because {@link Concert} uses
 * {@code @GeneratedValue(strategy=UUID)} (Hibernate always generates, no setter). Zones are persisted
 * via JPA (their id is free — resolved by name). Timestamps are set explicitly because the native
 * INSERT bypasses {@code @PrePersist}.
 *
 * <p>Gated by {@code app.dev.seed.enabled=true} (default false), NOT by Spring profile: both dev and
 * the prod image run {@code SPRING_PROFILES_ACTIVE=docker}. The flag is set only in
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

    /** Bare ticket-type names — MUST match inventory ticket_types.name (§6.10). No "Vé " prefix. */
    static final List<String[]> ZONES = List.of(
            new String[] {"SVIP", "SVIP Zone"},
            new String[] {"VIP", "VIP Zone"},
            new String[] {"CAT1", "Category 1"},
            new String[] {"CAT2", "Category 2"},
            new String[] {"GA", "General Admission"});

    private final EntityManager em;
    private final ConcertRepository concertRepository;

    public EventAnchorSeeder(EntityManager em, ConcertRepository concertRepository) {
        this.em = em;
        this.concertRepository = concertRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (concertRepository.existsById(ANCHOR_CONCERT_ID)) {
            log.info("Event anchor concert {} already exists — skipping seed", ANCHOR_CONCERT_ID);
            return;
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        OffsetDateTime saleStart = now.minusDays(1);
        OffsetDateTime saleEnd = OffsetDateTime.ofInstant(
                Instant.parse("2027-06-23T00:00:00Z"), ZoneOffset.UTC);
        OffsetDateTime eventDate = OffsetDateTime.ofInstant(
                Instant.now().plus(30, ChronoUnit.DAYS), ZoneOffset.UTC);

        seedVenueIfMissing(now);

        // Native INSERT to force the fixed concert id (@GeneratedValue would otherwise randomise it).
        em.createNativeQuery(
                        "INSERT INTO concerts "
                                + "(id, title, description, status, sale_start_at, sale_end_at, "
                                + "event_date, venue_id, reminder_sent, created_at, updated_at) "
                                + "VALUES (:id, :title, :description, :status, :saleStart, :saleEnd, "
                                + ":eventDate, :venueId, :reminderSent, :createdAt, :updatedAt)")
                .setParameter("id", ANCHOR_CONCERT_ID)
                .setParameter("title", "Tickefy Rehearsal Concert")
                .setParameter("description", "E2E baseline anchor concert (dev seed).")
                .setParameter("status", "PUBLISHED")
                .setParameter("saleStart", saleStart)
                .setParameter("saleEnd", saleEnd)
                .setParameter("eventDate", eventDate)
                .setParameter("venueId", ANCHOR_VENUE_ID)
                .setParameter("reminderSent", false)
                .setParameter("createdAt", now)
                .setParameter("updatedAt", now)
                .executeUpdate();
        em.flush();

        Concert anchorRef = em.getReference(Concert.class, ANCHOR_CONCERT_ID);
        for (String[] z : ZONES) {
            ConcertZone zone = new ConcertZone();
            zone.setConcert(anchorRef);
            zone.setTicketTypeName(z[0]); // BARE name
            zone.setZoneName(z[1]);
            em.persist(zone);
        }
        em.flush();

        log.info(
                "Event anchor seeded: concert {} PUBLISHED + venue {} + {} zones (bare names)",
                ANCHOR_CONCERT_ID,
                ANCHOR_VENUE_ID,
                ZONES.size());
    }

    private void seedVenueIfMissing(OffsetDateTime now) {
        Long count = ((Number) em.createNativeQuery("SELECT count(*) FROM venues WHERE id = :id")
                        .setParameter("id", ANCHOR_VENUE_ID)
                        .getSingleResult())
                .longValue();
        if (count > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO venues (id, name, address, city, capacity, created_at, updated_at) "
                                + "VALUES (:id, :name, :address, :city, :capacity, :createdAt, :updatedAt)")
                .setParameter("id", ANCHOR_VENUE_ID)
                .setParameter("name", "Tickefy Rehearsal Arena")
                .setParameter("address", "1 Rehearsal Way")
                .setParameter("city", "TP. Hồ Chí Minh")
                .setParameter("capacity", 25000)
                .setParameter("createdAt", now)
                .setParameter("updatedAt", now)
                .executeUpdate();
        em.flush();
    }
}
