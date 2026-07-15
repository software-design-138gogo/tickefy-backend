package com.tickefy.checkin.modules.vip.messaging;

import com.tickefy.checkin.modules.vip.entity.ProcessedMessageEntity;
import com.tickefy.checkin.modules.vip.entity.VipCacheMetaEntity;
import com.tickefy.checkin.modules.vip.repository.ProcessedMessageRepository;
import com.tickefy.checkin.modules.vip.repository.VipCacheMetaRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TX bean for vip-2 consumer (§8 — no self-invoke, TX ngắn, KHÔNG HTTP trong TX).
 * markStale + record dedup in 1 atomic TX.
 */
@Component
public class VipImportConsumerService {

    private final VipCacheMetaRepository metaRepo;
    private final ProcessedMessageRepository processedMessageRepo;

    public VipImportConsumerService(VipCacheMetaRepository metaRepo,
                                    ProcessedMessageRepository processedMessageRepo) {
        this.metaRepo = metaRepo;
        this.processedMessageRepo = processedMessageRepo;
    }

    @Transactional
    public void markStaleAndRecord(UUID messageId, String eventType, UUID concertId) {
        // mark-stale upsert (lastRefreshedAt NOT NULL → Instant.EPOCH khi tạo mới ép stale)
        VipCacheMetaEntity meta = metaRepo.findById(concertId)
                .map(m -> { m.setState("STALE"); return m; })
                .orElseGet(() -> new VipCacheMetaEntity(concertId, Instant.EPOCH, "STALE"));
        metaRepo.save(meta);

        processedMessageRepo.save(new ProcessedMessageEntity(messageId, eventType, Instant.now()));
    }
}
