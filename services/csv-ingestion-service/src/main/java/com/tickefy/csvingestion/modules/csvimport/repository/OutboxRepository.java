package com.tickefy.csvingestion.modules.csvimport.repository;

import com.tickefy.csvingestion.modules.csvimport.entity.OutboxEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    /** Drainer poll: oldest PENDING rows first. */
    List<OutboxEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /** Mark a row PUBLISHED after a successful publish — own short TX (publish stays outside any TX, §8). */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEntity o SET o.status = 'PUBLISHED', o.publishedAt = :now WHERE o.id = :id")
    int markPublished(@Param("id") UUID id, @Param("now") Instant now);

    /** Mark a row FAILED (unknown eventType — never publish a wrong routing key) — own short TX. */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEntity o SET o.status = 'FAILED' WHERE o.id = :id")
    int markEventFailed(@Param("id") UUID id);
}
