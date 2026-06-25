package com.tickefy.checkin.modules.vip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.checkin.common.exception.ApiException;
import com.tickefy.checkin.common.exception.ErrorCode;
import com.tickefy.checkin.modules.vip.client.CsvVipClient;
import com.tickefy.checkin.modules.vip.dto.VipGuestDto;
import com.tickefy.checkin.modules.vip.dto.VipGuestProjectionResponse;
import com.tickefy.checkin.modules.vip.entity.VipCacheMetaEntity;
import com.tickefy.checkin.modules.vip.entity.VipGuestProjectionEntity;
import com.tickefy.checkin.modules.vip.exception.CsvUnavailableException;
import com.tickefy.checkin.modules.vip.repository.VipCacheMetaRepository;
import com.tickefy.checkin.modules.vip.repository.VipGuestProjectionRepository;
import com.tickefy.checkin.modules.vip.service.VipProjectionService;
import com.tickefy.checkin.support.PostgresContainerITBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration tests for VipProjectionService — real Postgres (Testcontainers), mocked CsvVipClient.
 *
 * AC coverage:
 * AC-VPS-1  miss→pull→cache (FRESH, rows in DB)
 * AC-VPS-2  hit-fresh→no-pull (csvVipClient NOT called again)
 * AC-VPS-3  stale→repull (csvVipClient called again)
 * AC-VPS-4  email lookup — exact match + absent → empty page
 * AC-VPS-5  csv-down + existing cache → serve stale (no throw)
 * AC-VPS-6  csv-down + NO cache → throw ApiException SERVICE_UNAVAILABLE
 * AC-VPS-7  full-replace: 3 rows in, 2 come back from csv → cache = 2
 */
@SpringBootTest
@TestPropertySource(properties = {"app.vip.cache.ttl-min=5"})
class VipProjectionServiceIT extends PostgresContainerITBase {

    @MockitoBean
    private CsvVipClient csvVipClient;

    @Autowired
    private VipProjectionService service;

    @Autowired
    private VipGuestProjectionRepository projectionRepo;

    @Autowired
    private VipCacheMetaRepository metaRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID CONCERT_ID = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
    private static final UUID TICKET_TYPE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-ffffffffffff");
    private static final PageRequest PAGE = PageRequest.of(0, 50);

    @BeforeEach
    void clean() {
        // Use plain SQL so no JPA transaction context is required in @BeforeEach
        jdbcTemplate.update(
                "DELETE FROM checkin_service.vip_guest_projection WHERE concert_id = ?",
                CONCERT_ID);
        jdbcTemplate.update(
                "DELETE FROM checkin_service.vip_cache_meta WHERE concert_id = ?",
                CONCERT_ID);
    }

    // -------------------------------------------------------------------------
    // AC-VPS-1  miss→pull→cache
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_whenNoCacheExists_shouldPullFromCsvAndCacheResult() {
        List<VipGuestDto> csvData = List.of(
                new VipGuestDto("alice@test.com", "Alice", TICKET_TYPE_ID, "Gold VIP"),
                new VipGuestDto("bob@test.com", "Bob", TICKET_TYPE_ID, "Gold VIP"));
        when(csvVipClient.fetchAll(CONCERT_ID)).thenReturn(csvData);

        Page<VipGuestProjectionResponse> page = service.getVipGuests(CONCERT_ID, null, PAGE);

        verify(csvVipClient, times(1)).fetchAll(CONCERT_ID);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(projectionRepo.findByConcertId(CONCERT_ID)).hasSize(2);

        VipCacheMetaEntity meta = metaRepo.findById(CONCERT_ID).orElseThrow();
        assertThat(meta.getState()).isEqualTo("FRESH");
        assertThat(meta.getLastRefreshedAt()).isAfter(Instant.now().minusSeconds(10));
    }

    // -------------------------------------------------------------------------
    // AC-VPS-2  hit-fresh→no-pull
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_whenCacheFreshAndInTtl_shouldNotCallCsvAgain() {
        List<VipGuestDto> csvData = List.of(
                new VipGuestDto("alice@test.com", "Alice", TICKET_TYPE_ID, "Gold VIP"));
        when(csvVipClient.fetchAll(CONCERT_ID)).thenReturn(csvData);

        // First call: populate cache
        service.getVipGuests(CONCERT_ID, null, PAGE);

        // Second call: should serve from cache
        Page<VipGuestProjectionResponse> page2 = service.getVipGuests(CONCERT_ID, null, PAGE);

        // Total across BOTH calls: only 1 invocation
        verify(csvVipClient, times(1)).fetchAll(CONCERT_ID);
        assertThat(page2.getTotalElements()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC-VPS-3  stale→repull
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_whenCacheIsStale_shouldRepullFromCsv() {
        List<VipGuestDto> csvData = List.of(
                new VipGuestDto("alice@test.com", "Alice", TICKET_TYPE_ID, "Gold VIP"));
        when(csvVipClient.fetchAll(CONCERT_ID)).thenReturn(csvData);

        // First call: populate cache (FRESH)
        service.getVipGuests(CONCERT_ID, null, PAGE);

        // Force stale: set lastRefreshedAt to 10 minutes ago (TTL=5 min)
        VipCacheMetaEntity meta = metaRepo.findById(CONCERT_ID).orElseThrow();
        meta.setLastRefreshedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        metaRepo.save(meta);

        // Second call: stale → must re-pull
        service.getVipGuests(CONCERT_ID, null, PAGE);

        verify(csvVipClient, times(2)).fetchAll(CONCERT_ID);
    }

    // -------------------------------------------------------------------------
    // AC-VPS-4  email lookup — exact match + absent email → empty page
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_emailFilter_shouldReturnExactMatchAndEmptyForAbsent() {
        List<VipGuestDto> csvData = List.of(
                new VipGuestDto("alice@test.com", "Alice", TICKET_TYPE_ID, "Gold VIP"),
                new VipGuestDto("charlie@test.com", "Charlie", TICKET_TYPE_ID, "Silver VIP"),
                new VipGuestDto("diana@test.com", "Diana", TICKET_TYPE_ID, "Silver VIP"));
        when(csvVipClient.fetchAll(CONCERT_ID)).thenReturn(csvData);

        // Populate
        service.getVipGuests(CONCERT_ID, null, PAGE);

        // Exact match
        Page<VipGuestProjectionResponse> match = service.getVipGuests(CONCERT_ID, "alice@test.com", PAGE);
        assertThat(match.getTotalElements()).isEqualTo(1);
        assertThat(match.getContent().get(0).email()).isEqualTo("alice@test.com");

        // Non-existing
        Page<VipGuestProjectionResponse> absent = service.getVipGuests(CONCERT_ID, "notexist@test.com", PAGE);
        assertThat(absent.getTotalElements()).isZero();
    }

    // -------------------------------------------------------------------------
    // AC-VPS-5  csv-down + existing cache → serve stale, no exception
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_whenCsvDownAndCacheExists_shouldServeStaleWithoutThrowing() {
        // Seed cache via first successful pull
        List<VipGuestDto> csvData = List.of(
                new VipGuestDto("alice@test.com", "Alice", TICKET_TYPE_ID, "Gold VIP"));
        when(csvVipClient.fetchAll(CONCERT_ID)).thenReturn(csvData);
        service.getVipGuests(CONCERT_ID, null, PAGE);

        // Force stale so next call tries to re-pull
        VipCacheMetaEntity meta = metaRepo.findById(CONCERT_ID).orElseThrow();
        meta.setLastRefreshedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        metaRepo.save(meta);

        // Now csv is down
        when(csvVipClient.fetchAll(CONCERT_ID)).thenThrow(new CsvUnavailableException("csv down"));

        // Should NOT throw — serve stale
        Page<VipGuestProjectionResponse> page = service.getVipGuests(CONCERT_ID, null, PAGE);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("alice@test.com");
    }

    // -------------------------------------------------------------------------
    // AC-VPS-6  csv-down + NO cache → throw ApiException SERVICE_UNAVAILABLE
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_whenCsvDownAndNoCacheExists_shouldThrowServiceUnavailableApiException() {
        when(csvVipClient.fetchAll(CONCERT_ID)).thenThrow(new CsvUnavailableException("csv down"));

        assertThatThrownBy(() -> service.getVipGuests(CONCERT_ID, null, PAGE))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getStatus().value()).isEqualTo(503);
                });
    }

    // -------------------------------------------------------------------------
    // AC-VPS-7  full-replace: 3 rows seeded, csv returns 2 → cache = 2
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_afterRefresh_shouldFullyReplaceOldCacheWithNewCsvData() {
        // Seed 3 rows via first pull
        List<VipGuestDto> initial = List.of(
                new VipGuestDto("a@test.com", "A", TICKET_TYPE_ID, "VIP"),
                new VipGuestDto("b@test.com", "B", TICKET_TYPE_ID, "VIP"),
                new VipGuestDto("c@test.com", "C", TICKET_TYPE_ID, "VIP"));
        when(csvVipClient.fetchAll(CONCERT_ID)).thenReturn(initial);
        service.getVipGuests(CONCERT_ID, null, PAGE);
        assertThat(projectionRepo.findByConcertId(CONCERT_ID)).hasSize(3);

        // Force stale
        VipCacheMetaEntity meta = metaRepo.findById(CONCERT_ID).orElseThrow();
        meta.setLastRefreshedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        metaRepo.save(meta);

        // CSV now returns only 2 (c@test.com removed)
        List<VipGuestDto> updated = List.of(
                new VipGuestDto("a@test.com", "A", TICKET_TYPE_ID, "VIP"),
                new VipGuestDto("b@test.com", "B", TICKET_TYPE_ID, "VIP"));
        when(csvVipClient.fetchAll(CONCERT_ID)).thenReturn(updated);

        service.getVipGuests(CONCERT_ID, null, PAGE);

        List<VipGuestProjectionEntity> cached = projectionRepo.findByConcertId(CONCERT_ID);
        assertThat(cached).hasSize(2);
        assertThat(cached).extracting(VipGuestProjectionEntity::getEmail)
                .containsExactlyInAnyOrder("a@test.com", "b@test.com")
                .doesNotContain("c@test.com");
    }
}
