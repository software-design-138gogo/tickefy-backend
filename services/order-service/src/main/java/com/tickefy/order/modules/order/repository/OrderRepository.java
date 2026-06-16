package com.tickefy.order.modules.order.repository;

import com.tickefy.order.modules.order.entity.OrderEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);

    Page<OrderEntity> findByUserId(UUID userId, Pageable pageable);

    /** Expire worker: PAYMENT_PENDING orders past their expiry. */
    List<OrderEntity> findByStatusAndExpiresAtBefore(String status, Instant cutoff);
}
