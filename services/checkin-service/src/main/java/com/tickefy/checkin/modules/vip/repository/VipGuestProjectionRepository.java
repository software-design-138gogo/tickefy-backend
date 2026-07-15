package com.tickefy.checkin.modules.vip.repository;

import com.tickefy.checkin.modules.vip.entity.VipGuestProjectionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VipGuestProjectionRepository
        extends JpaRepository<VipGuestProjectionEntity, UUID> {

    Page<VipGuestProjectionEntity> findByConcertId(UUID concertId, Pageable pageable);

    Page<VipGuestProjectionEntity> findByConcertIdAndEmail(
            UUID concertId, String email, Pageable pageable);

    List<VipGuestProjectionEntity> findByConcertId(UUID concertId);

    @Modifying
    @Query("DELETE FROM VipGuestProjectionEntity v WHERE v.concertId = :cid")
    void deleteByConcertId(@Param("cid") UUID cid);
}
