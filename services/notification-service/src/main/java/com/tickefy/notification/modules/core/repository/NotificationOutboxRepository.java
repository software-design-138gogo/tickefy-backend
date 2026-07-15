package com.tickefy.notification.modules.core.repository;

import com.tickefy.notification.modules.core.entity.NotificationOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;

@Repository
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT o FROM NotificationOutbox o WHERE o.status = 'PENDING' AND (o.nextRetryAt IS NULL OR o.nextRetryAt <= :now) ORDER BY o.createdAt ASC")
    List<NotificationOutbox> findPendingForUpdate(@org.springframework.data.repository.query.Param("now") LocalDateTime now, Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE NotificationOutbox o SET o.status = 'PENDING', o.retryCount = 0 WHERE o.status = 'PROCESSING' AND o.updatedAt <= :threshold")
    int resetStuckProcessingRecords(@org.springframework.data.repository.query.Param("threshold") LocalDateTime threshold);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM NotificationOutbox o WHERE o.status = 'SENT' AND o.updatedAt <= :threshold")
    int deleteOldSentRecords(@org.springframework.data.repository.query.Param("threshold") LocalDateTime threshold);
}
