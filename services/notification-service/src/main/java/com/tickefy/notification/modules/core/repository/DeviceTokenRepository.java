package com.tickefy.notification.modules.core.repository;

import com.tickefy.notification.modules.core.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {
    Optional<DeviceToken> findByUserIdAndToken(UUID userId, String token);

    java.util.List<DeviceToken> findByUserId(UUID userId);
}
