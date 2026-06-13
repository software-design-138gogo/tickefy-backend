package com.tickefy.inventory.modules.inventory.repository;

import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketTypeRepository extends JpaRepository<TicketTypeEntity, UUID> {

    List<TicketTypeEntity> findByConcertId(UUID concertId);
}
