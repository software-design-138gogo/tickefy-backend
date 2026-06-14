package com.tickefy.checkin.modules.checkin.repository;

import com.tickefy.checkin.modules.checkin.entity.SyncBatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncBatchRepository extends JpaRepository<SyncBatch, UUID> {
    Optional<SyncBatch> findBySyncBatchId(String syncBatchId);
}
