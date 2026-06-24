package com.tickefy.notification.modules.notification.service;

import com.tickefy.notification.modules.core.entity.DeviceToken;
import com.tickefy.notification.modules.core.repository.DeviceTokenRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing user device tokens (FCM, APNs, etc.) for push notifications.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * Registers a new device token or updates the last used time if it already exists.
     */
    @Transactional
    public void registerToken(UUID userId, String token, String deviceType, String deviceId) {
        Optional<DeviceToken> existingToken = deviceTokenRepository.findByUserIdAndToken(userId, token);

        if (existingToken.isPresent()) {
            // Token already exists, and we don't have lastUsedAt field in this entity version.
            // Just return to avoid duplicate inserts.
            log.debug("Device token already registered. userId={}", userId);
        } else {
            DeviceToken newToken = DeviceToken.builder()
                    .userId(userId)
                    .token(token)
                    .deviceType(deviceType)
                    .build();
            deviceTokenRepository.save(newToken);
            log.info("Registered new device token for user. userId={}", userId);
        }
    }

    /**
     * Unregisters (deactivates) a device token, typically on logout.
     */
    @Transactional
    public void unregisterToken(UUID userId, String token) {
        deviceTokenRepository.findByUserIdAndToken(userId, token).ifPresent(dt -> {
            deviceTokenRepository.delete(dt);
            log.info("Unregistered device token for user. userId={}", userId);
        });
    }

    /**
     * Retrieves all active device tokens for a given user.
     */
    @Transactional(readOnly = true)
    public List<DeviceToken> getActiveTokens(UUID userId) {
        // Since we don't have an isActive flag, all present tokens are considered active
        return deviceTokenRepository.findByUserId(userId);
    }
}
