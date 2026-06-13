package com.tickefy.inventory.modules.inventory.repository;

import com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketReservationRepository extends JpaRepository<TicketReservationEntity, UUID> {

    Optional<TicketReservationEntity> findByOrderIdAndTicketTypeId(UUID orderId, UUID ticketTypeId);

    /**
     * Sum active (RESERVED + COMMITTED) quantity for a user on a ticket type.
     * Used for per-user limit enforcement in DB fallback path.
     */
    @Query(
            "SELECT COALESCE(SUM(r.quantity), 0) FROM TicketReservationEntity r "
                    + "WHERE r.userId = :userId AND r.ticketTypeId = :ticketTypeId "
                    + "AND r.status IN ('RESERVED', 'COMMITTED')")
    int sumActiveQuantity(@Param("userId") UUID userId, @Param("ticketTypeId") UUID ticketTypeId);

    @Query(
            "SELECT r FROM TicketReservationEntity r "
                    + "WHERE r.ticketTypeId = :ticketTypeId AND r.status IN ('RESERVED', 'COMMITTED')")
    List<TicketReservationEntity> findActiveByTicketTypeId(@Param("ticketTypeId") UUID ticketTypeId);
}
