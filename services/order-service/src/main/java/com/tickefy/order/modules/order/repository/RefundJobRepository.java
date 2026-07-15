package com.tickefy.order.modules.order.repository;

import com.tickefy.order.modules.order.entity.RefundJobEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJobRepository extends JpaRepository<RefundJobEntity, UUID> {

    Optional<RefundJobEntity> findByConcertId(UUID concertId);

    boolean existsByConcertId(UUID concertId);

    List<RefundJobEntity> findAllByStatus(String status);
}
