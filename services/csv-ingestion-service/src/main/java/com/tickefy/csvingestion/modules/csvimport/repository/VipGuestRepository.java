package com.tickefy.csvingestion.modules.csvimport.repository;

import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VipGuestRepository extends JpaRepository<VipGuestEntity, UUID> {

    List<VipGuestEntity> findByConcertId(UUID concertId);

    boolean existsByConcertIdAndEmail(UUID concertId, String email);
}
