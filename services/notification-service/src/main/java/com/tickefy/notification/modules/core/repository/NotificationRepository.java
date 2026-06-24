package com.tickefy.notification.modules.core.repository;

import com.tickefy.notification.modules.core.entity.Notification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for in-app {@link Notification} records. */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Returns a paginated list of notifications for the given user, ordered by creation time
     * descending (newest first).
     */
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Finds a single notification by its ID AND the owning user — prevents cross-user data leaks.
     */
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);
}
