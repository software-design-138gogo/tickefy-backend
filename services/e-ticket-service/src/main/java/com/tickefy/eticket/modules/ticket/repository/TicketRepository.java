package com.tickefy.eticket.modules.ticket.repository;

import com.tickefy.eticket.modules.ticket.entity.Ticket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    List<Ticket> findByUserId(String userId);

    Optional<Ticket> findByQrToken(String qrToken);

    Optional<Ticket> findByOrderItemId(String orderItemId);

    Optional<Ticket> findByIdAndUserId(UUID id, String userId);

    List<Ticket> findByConcertIdAndStatus(String concertId, com.tickefy.eticket.modules.ticket.entity.TicketStatus status);

    /**
     * Atomic check-in: sets CHECKED_IN only when current status = ISSUED.
     * Returns 1 = ACCEPTED, 0 = DUPLICATE_REJECTED (already checked-in or wrong state).
     */
    @Modifying
    @Query("""
            UPDATE Ticket t
            SET t.status = com.tickefy.eticket.modules.ticket.entity.TicketStatus.CHECKED_IN,
                t.checkedInAt = :now,
                t.updatedAt   = :now
            WHERE t.id = :id
              AND t.status = com.tickefy.eticket.modules.ticket.entity.TicketStatus.ISSUED
            """)
    int checkIn(@Param("id") UUID id, @Param("now") java.time.Instant now);

    /**
     * Atomic QR check-in used by checkin-service. The concert predicate prevents
     * a wrong-concert scan from mutating ticket state before validation.
     */
    @Modifying
    @Query("""
            UPDATE Ticket t
            SET t.status = com.tickefy.eticket.modules.ticket.entity.TicketStatus.CHECKED_IN,
                t.checkedInAt = :now,
                t.updatedAt   = :now
            WHERE t.qrToken = :qrToken
              AND t.concertId = :concertId
              AND t.status = com.tickefy.eticket.modules.ticket.entity.TicketStatus.ISSUED
            """)
    int checkInByQrTokenAndConcertId(
            @Param("qrToken") String qrToken,
            @Param("concertId") String concertId,
            @Param("now") java.time.Instant now);
}
