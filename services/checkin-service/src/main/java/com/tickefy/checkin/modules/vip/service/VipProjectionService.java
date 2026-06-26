package com.tickefy.checkin.modules.vip.service;

import com.tickefy.checkin.common.exception.ApiException;
import com.tickefy.checkin.common.exception.ErrorCode;
import com.tickefy.checkin.modules.vip.client.CsvVipClient;
import com.tickefy.checkin.modules.vip.dto.VipGuestDto;
import com.tickefy.checkin.modules.vip.dto.VipGuestProjectionResponse;
import com.tickefy.checkin.modules.vip.dto.VipGuestSnapshotDto;
import com.tickefy.checkin.modules.vip.entity.VipCacheMetaEntity;
import com.tickefy.checkin.modules.vip.mapper.VipGuestMapper;
import com.tickefy.checkin.modules.vip.repository.VipCacheMetaRepository;
import com.tickefy.checkin.modules.vip.repository.VipGuestProjectionRepository;
import com.tickefy.checkin.modules.vip.exception.CsvUnavailableException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Non-transactional orchestrator for VIP guest projection reads.
 * fetch (HTTP) and TX-mutation are in separate beans to respect §8 (no HTTP in TX)
 * and avoid Spring self-invocation proxy bypass.
 */
@Service
public class VipProjectionService {

    private static final Logger log = LoggerFactory.getLogger(VipProjectionService.class);

    private final CsvVipClient csvVipClient;
    private final VipGuestProjectionRepository projectionRepo;
    private final VipCacheMetaRepository metaRepo;
    private final VipProjectionPersistence persistence;
    private final long ttlMin;

    public VipProjectionService(
            CsvVipClient csvVipClient,
            VipGuestProjectionRepository projectionRepo,
            VipCacheMetaRepository metaRepo,
            VipProjectionPersistence persistence,
            @Value("${app.vip.cache.ttl-min:5}") long ttlMin) {
        this.csvVipClient = csvVipClient;
        this.projectionRepo = projectionRepo;
        this.metaRepo = metaRepo;
        this.persistence = persistence;
        this.ttlMin = ttlMin;
    }

    /**
     * Returns a page of VIP guests for a concert, refreshing the projection cache when stale.
     * email filter is optional — null returns all.
     */
    public Page<VipGuestProjectionResponse> getVipGuests(
            UUID concertId, String email, Pageable pageable) {
        ensureFresh(concertId);
        Page<com.tickefy.checkin.modules.vip.entity.VipGuestProjectionEntity> page;
        if (email == null || email.isBlank()) {
            page = projectionRepo.findByConcertId(concertId, pageable);
        } else {
            page = projectionRepo.findByConcertIdAndEmail(
                    concertId, normalizeEmail(email), pageable);
        }
        return page.map(VipGuestMapper::toResponse);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns ALL VIP guests as a snapshot list (for offline use by vip-3 seam).
     * Ensures freshness before reading.
     */
    public List<VipGuestSnapshotDto> getVipGuestsForSnapshot(UUID concertId) {
        ensureFresh(concertId);
        return projectionRepo.findByConcertId(concertId).stream()
                .map(VipGuestMapper::toSnapshotDto)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Internal cache management
    // -------------------------------------------------------------------------

    private void ensureFresh(UUID concertId) {
        Optional<VipCacheMetaEntity> metaOpt = metaRepo.findById(concertId);
        boolean needsRefresh = metaOpt.isEmpty()
                || !"FRESH".equals(metaOpt.get().getState())
                || metaOpt.get().getLastRefreshedAt()
                        .isBefore(Instant.now().minus(ttlMin, ChronoUnit.MINUTES));
        if (needsRefresh) {
            refresh(concertId);
        }
    }

    /**
     * Refresh order (STRICTLY enforced per PLAN §D):
     *  1. fetchAll — complete page-through, OUTSIDE any TX.
     *  2. Only on full success: persistence.replaceAll (short TX, delete+saveAll+meta).
     *  3. On CsvUnavailableException: serve stale if cache exists, else throw 503.
     *
     * TUYỆT ĐỐI KHÔNG delete trước fetch.
     */
    private void refresh(UUID concertId) {
        List<VipGuestDto> rows;
        try {
            rows = csvVipClient.fetchAll(concertId);
        } catch (CsvUnavailableException ex) {
            boolean hasCache = !projectionRepo
                    .findByConcertId(concertId, PageRequest.of(0, 1))
                    .isEmpty();
            if (!hasCache) {
                throw new ApiException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "VIP source unavailable and no cached data",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
            log.warn("CsvVipClient unavailable for concertId={}, serving stale cache", concertId);
            return;
        }
        // Fetch completed successfully — now atomically replace
        persistence.replaceAll(concertId, rows);
    }
}
