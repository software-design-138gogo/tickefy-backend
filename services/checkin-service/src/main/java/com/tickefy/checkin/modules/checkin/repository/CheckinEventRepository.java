package com.tickefy.checkin.modules.checkin.repository;

import com.tickefy.checkin.modules.checkin.entity.CheckinEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckinEventRepository extends JpaRepository<CheckinEvent, UUID> {
    List<CheckinEvent> findByConcertIdOrderByScannedAtDesc(String concertId);

    @Query("""
            SELECT e
            FROM CheckinEvent e
            WHERE e.concertId = :concertId
              AND (:gate IS NULL OR e.gate = :gate)
              AND (:staffId IS NULL OR e.staffId = :staffId)
              AND (:result IS NULL OR e.result = :result)
            ORDER BY e.scannedAt DESC
            """)
    Page<CheckinEvent> searchHistory(
            @Param("concertId") String concertId,
            @Param("gate") String gate,
            @Param("staffId") String staffId,
            @Param("result") String result,
            Pageable pageable);
}
