package com.tickefy.order.modules.order.repository;

import com.tickefy.order.modules.order.entity.OutboxEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    /** Drainer poll: oldest PENDING rows first. */
    List<OutboxEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}
