package com.tickefy.inventory.modules.inventory.repository;

import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketTypeRepository extends JpaRepository<TicketTypeEntity, UUID> {

    List<TicketTypeEntity> findByConcertId(UUID concertId);

    /**
     * Bulk-marks every ticket type of a cancelled concert. JPQL (entity-based → schema-aware, NOT
     * native → avoids search_path pitfalls). Idempotent: re-running sets the same value (already true)
     * — final state converges regardless of redelivery.
     */
    @Modifying
    @Query("UPDATE TicketTypeEntity t SET t.concertCancelled = true WHERE t.concertId = :concertId")
    int markConcertCancelled(@Param("concertId") UUID concertId);
}
