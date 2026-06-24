package com.tickefy.csvingestion.modules.csvimport.repository;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportJobRepository extends JpaRepository<ImportJobEntity, UUID> {

    /**
     * Atomic state-guard claim (CLAUDE §6.9): PENDING -> PROCESSING, only one worker wins.
     * Returns affected row count (1 = claimed, 0 = already claimed/terminal/missing).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ImportJobEntity j SET j.status = 'PROCESSING', j.startedAt = :now, "
            + "j.attemptCount = j.attemptCount + 1 WHERE j.id = :id AND j.status = 'PENDING'")
    int claim(@Param("id") UUID id, @Param("now") Instant now);
}
