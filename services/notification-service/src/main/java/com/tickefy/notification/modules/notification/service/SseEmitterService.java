package com.tickefy.notification.modules.notification.service;

import com.tickefy.notification.modules.core.entity.Notification;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Service for managing Server-Sent Events (SSE) connections.
 * Allows pushing real-time in-app notifications to connected users.
 */
@Slf4j
@Service
public class SseEmitterService {

    // Store active SSE connections by user ID. 
    // In a distributed environment, this would need to be backed by Redis Pub/Sub,
    // but a ConcurrentHashMap is sufficient for a single instance (Phase 2).
    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Creates and registers a new SSE connection for a user.
     */
    public SseEmitter createEmitter(UUID userId) {
        // Set timeout to 30 minutes (1800000ms). Spring Boot defaults might be shorter.
        SseEmitter emitter = new SseEmitter(1800000L);
        emitters.put(userId, emitter);

        emitter.onCompletion(() -> {
            log.debug("SSE completed for user: {}", userId);
            emitters.remove(userId);
        });

        emitter.onTimeout(() -> {
            log.debug("SSE timeout for user: {}", userId);
            emitter.complete();
            emitters.remove(userId);
        });

        emitter.onError((e) -> {
            log.debug("SSE error for user: {}", userId, e);
            emitter.completeWithError(e);
            emitters.remove(userId);
        });

        // Send an initial dummy event to establish connection immediately
        try {
            emitter.send(SseEmitter.event().name("INIT").data("Connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            emitters.remove(userId);
        }

        return emitter;
    }

    /**
     * Sends a real-time notification to a specific user if they are connected.
     */
    public void sendNotification(UUID userId, Notification notification) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("NOTIFICATION")
                        .data(notification));
                log.info("Pushed SSE notification to user: {}", userId);
            } catch (IOException e) {
                log.warn("Failed to push SSE to user: {}. Removing emitter.", userId);
                emitter.completeWithError(e);
                emitters.remove(userId);
            }
        } else {
            log.debug("User {} is not connected via SSE. Notification saved only.", userId);
        }
    }
}
