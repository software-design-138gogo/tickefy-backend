package com.tickefy.event.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.event.modules.concert.Concert;
import com.tickefy.event.modules.concert.ConcertRepository;
import com.tickefy.event.modules.concert.ConcertStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * EventAnchorSeeder runs native INSERT SQL that must execute on real Postgres (not H2), so this uses a
 * Testcontainers Postgres with Flyway-built schema. Verifies: the fixed anchor concert/venue/zones are
 * created with BARE ticket-type names, and re-running the seeder is idempotent (no duplicate).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class EventAnchorSeederTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("event_test")
                    .withUsername("event_test")
                    .withPassword("event_test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
    }

    @Autowired private EntityManager em;
    @Autowired private ConcertRepository concertRepository;

    @Test
    void seeds_anchor_concert_with_bare_zone_names_and_is_idempotent() {
        EventAnchorSeeder seeder =
                new EventAnchorSeeder(em, concertRepository, EventAnchorSeeder.DEFAULT_SEED_ORGANIZER_ID);

        seeder.run(null);
        em.flush();
        em.clear();

        Concert c = concertRepository.findById(EventAnchorSeeder.ANCHOR_CONCERT_ID).orElseThrow();
        assertThat(c.getStatus()).isEqualTo(ConcertStatus.PUBLISHED);
        assertThat(c.getVenue()).isNotNull();
        assertThat(c.getVenue().getId()).isEqualTo(EventAnchorSeeder.ANCHOR_VENUE_ID);

        List<String> zoneNames = em.createQuery(
                        "SELECT z.ticketTypeName FROM ConcertZone z WHERE z.concert.id = :cid",
                        String.class)
                .setParameter("cid", EventAnchorSeeder.ANCHOR_CONCERT_ID)
                .getResultList();
        assertThat(zoneNames).containsExactlyInAnyOrder("SVIP", "VIP", "CAT1", "CAT2", "GA");
        assertThat(zoneNames).noneMatch(n -> n.startsWith("Vé "));

        // Second run — idempotent guard, no duplicate concert nor zones.
        seeder.run(null);
        em.flush();
        em.clear();

        Long concertCount = em.createQuery(
                        "SELECT count(c) FROM Concert c WHERE c.id = :cid", Long.class)
                .setParameter("cid", EventAnchorSeeder.ANCHOR_CONCERT_ID)
                .getSingleResult();
        assertThat(concertCount).isEqualTo(1L);

        Long zoneCount = em.createQuery(
                        "SELECT count(z) FROM ConcertZone z WHERE z.concert.id = :cid", Long.class)
                .setParameter("cid", EventAnchorSeeder.ANCHOR_CONCERT_ID)
                .getSingleResult();
        assertThat(zoneCount).isEqualTo(5L);
    }

    @Test
    void seeds_all_seven_concerts_each_with_five_bare_zones_no_diamond_and_is_idempotent() {
        EventAnchorSeeder seeder =
                new EventAnchorSeeder(em, concertRepository, EventAnchorSeeder.DEFAULT_SEED_ORGANIZER_ID);

        seeder.run(null);
        em.flush();
        em.clear();

        // Every fixed concert id present, PUBLISHED, venue linked, exactly the 5 bare zones.
        List<UUID> ids = new java.util.ArrayList<>();
        ids.add(EventAnchorSeeder.ANCHOR_CONCERT_ID);
        EventAnchorSeeder.DEMO_CONCERTS.forEach(dc -> ids.add(dc.id()));
        EventAnchorSeeder.SALE_WINDOW_CONCERTS.forEach(sw -> ids.add(sw.id()));

        UUID expectedOrganizer = UUID.fromString(EventAnchorSeeder.DEFAULT_SEED_ORGANIZER_ID);
        for (UUID id : ids) {
            Concert c = concertRepository.findById(id).orElseThrow();
            assertThat(c.getStatus()).isEqualTo(ConcertStatus.PUBLISHED);
            assertThat(c.getVenue()).isNotNull();
            // created_by must be the configured organizer (non-null) — else ai-bio rejects with 503.
            assertThat(c.getCreatedBy()).isEqualTo(expectedOrganizer);

            List<String> zoneNames = em.createQuery(
                            "SELECT z.ticketTypeName FROM ConcertZone z WHERE z.concert.id = :cid",
                            String.class)
                    .setParameter("cid", id)
                    .getResultList();
            assertThat(zoneNames).containsExactlyInAnyOrder("SVIP", "VIP", "CAT1", "CAT2", "GA");
        }

        // No "Vé " prefix and no DIAMOND anywhere.
        List<String> allZoneNames = em.createQuery(
                        "SELECT z.ticketTypeName FROM ConcertZone z", String.class)
                .getResultList();
        assertThat(allZoneNames).noneMatch(n -> n.startsWith("Vé "));
        assertThat(allZoneNames).doesNotContain("DIAMOND");

        // Sale-window concerts (C3): 0005 not open yet (future saleStart), 0006 closed (past saleEnd).
        Instant nowRef = Instant.now();
        UUID notStarted = UUID.fromString("c1c1c1c1-0000-4000-8000-000000000005");
        UUID closed = UUID.fromString("c1c1c1c1-0000-4000-8000-000000000006");
        Instant notStartedSaleStart = em.createQuery(
                        "SELECT c.saleStartAt FROM Concert c WHERE c.id = :id", Instant.class)
                .setParameter("id", notStarted)
                .getSingleResult();
        assertThat(notStartedSaleStart).isAfter(nowRef);
        Instant closedSaleEnd = em.createQuery(
                        "SELECT c.saleEndAt FROM Concert c WHERE c.id = :id", Instant.class)
                .setParameter("id", closed)
                .getSingleResult();
        assertThat(closedSaleEnd).isBefore(nowRef);

        // Second run — idempotent: still exactly 7 concerts and 35 zones (5 each).
        seeder.run(null);
        em.flush();
        em.clear();

        Long concertCount =
                em.createQuery("SELECT count(c) FROM Concert c", Long.class).getSingleResult();
        assertThat(concertCount).isEqualTo(7L);

        Long zoneCount =
                em.createQuery("SELECT count(z) FROM ConcertZone z", Long.class).getSingleResult();
        assertThat(zoneCount).isEqualTo(35L);
    }
}
