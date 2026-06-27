package com.tickefy.order.modules.order.repository;

import com.tickefy.order.modules.order.entity.OrderEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);

    Page<OrderEntity> findByUserId(UUID userId, Pageable pageable);

    /** Expire worker: PAYMENT_PENDING orders past their expiry. */
    List<OrderEntity> findByStatusAndExpiresAtBefore(String status, Instant cutoff);

    /**
     * Refund leg (ConcertCancelled consumer): flip every PAID order of a cancelled concert to
     * REFUND_PENDING in one statement. The {@code status = 'PAID'} guard encodes the only valid
     * state-machine edge (PAID → REFUND_PENDING) and makes redelivery a natural no-op (re-run
     * matches 0 rows). JPQL (not native) keeps the schema search_path safe. @PreUpdate is bypassed
     * by bulk updates, so updated_at is set explicitly. OrderEntity has no @Version → no bump needed.
     *
     * @return number of orders moved to REFUND_PENDING
     */
    @Modifying
    @Query("UPDATE OrderEntity o SET o.status = 'REFUND_PENDING', o.updatedAt = :now "
            + "WHERE o.concertId = :concertId AND o.status = 'PAID'")
    int markConcertPaidOrdersRefundPending(@Param("concertId") UUID concertId, @Param("now") Instant now);
}
