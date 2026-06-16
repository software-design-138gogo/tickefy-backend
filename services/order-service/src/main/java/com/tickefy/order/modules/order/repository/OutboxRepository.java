package com.tickefy.order.modules.order.repository;

import com.tickefy.order.modules.order.entity.OutboxEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {
}
