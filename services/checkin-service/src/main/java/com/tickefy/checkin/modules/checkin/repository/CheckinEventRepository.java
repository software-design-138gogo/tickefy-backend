package com.tickefy.checkin.modules.checkin.repository;

import com.tickefy.checkin.modules.checkin.entity.CheckinEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinEventRepository extends JpaRepository<CheckinEvent, UUID> {
    List<CheckinEvent> findByConcertIdOrderByScannedAtDesc(String concertId);
}
