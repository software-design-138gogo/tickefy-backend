package com.tickefy.inventory.modules.inventory.repository;

import com.tickefy.inventory.modules.inventory.entity.TicketTypeInventoryEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketTypeInventoryRepository extends JpaRepository<TicketTypeInventoryEntity, UUID> {

    Optional<TicketTypeInventoryEntity> findByTicketTypeId(UUID ticketTypeId);

    /**
     * Conditional UPDATE: increment reserved_qty only if enough available.
     * Returns 1 if update succeeded, 0 if not enough stock.
     */
    @Modifying
    @Query(
            "UPDATE TicketTypeInventoryEntity i "
                    + "SET i.reservedQty = i.reservedQty + :qty "
                    + "WHERE i.ticketTypeId = :id "
                    + "AND (i.totalQty - i.soldQty - i.reservedQty) >= :qty")
    int incrementReservedConditional(@Param("id") UUID id, @Param("qty") int qty);
}
