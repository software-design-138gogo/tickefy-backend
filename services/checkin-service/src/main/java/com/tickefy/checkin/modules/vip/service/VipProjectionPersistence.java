package com.tickefy.checkin.modules.vip.service;

import com.tickefy.checkin.modules.vip.dto.VipGuestDto;
import com.tickefy.checkin.modules.vip.entity.VipCacheMetaEntity;
import com.tickefy.checkin.modules.vip.entity.VipGuestProjectionEntity;
import com.tickefy.checkin.modules.vip.mapper.VipGuestMapper;
import com.tickefy.checkin.modules.vip.repository.VipCacheMetaRepository;
import com.tickefy.checkin.modules.vip.repository.VipGuestProjectionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin TX proxy for VIP projection persistence.
 * All DB mutations happen in a single short transaction — NO HTTP calls inside.
 * Separated from VipProjectionService to avoid Spring self-invocation proxy bypass (see §8).
 */
@Component
public class VipProjectionPersistence {

    private final VipGuestProjectionRepository projectionRepo;
    private final VipCacheMetaRepository metaRepo;

    public VipProjectionPersistence(
            VipGuestProjectionRepository projectionRepo,
            VipCacheMetaRepository metaRepo) {
        this.projectionRepo = projectionRepo;
        this.metaRepo = metaRepo;
    }

    /**
     * Atomically replaces all VIP projection rows for a concert and upserts cache meta.
     * Must only be called AFTER a successful full fetchAll() from CsvVipClient.
     */
    @Transactional
    public void replaceAll(UUID concertId, List<VipGuestDto> rows) {
        projectionRepo.deleteByConcertId(concertId);
        projectionRepo.flush();

        List<VipGuestProjectionEntity> entities = rows.stream()
                .map(dto -> VipGuestMapper.toEntity(concertId, dto))
                .toList();
        projectionRepo.saveAll(entities);

        VipCacheMetaEntity meta = metaRepo.findById(concertId)
                .orElseGet(() -> {
                    VipCacheMetaEntity m = new VipCacheMetaEntity();
                    m.setConcertId(concertId);
                    return m;
                });
        meta.setLastRefreshedAt(Instant.now());
        meta.setState("FRESH");
        metaRepo.save(meta);
    }
}
