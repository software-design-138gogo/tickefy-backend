package com.tickefy.notification.modules.notification.strategy;

import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.notification.service.SseEmitterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseNotificationStrategy implements NotificationChannelStrategy {

    private final SseEmitterService sseEmitterService;

    @Override
    public boolean supports(NotificationContext context) {
        return context.getNotification() != null;
    }

    @Override
    public void send(NotificationContext context) {
        Notification notification = context.getNotification();
        if (notification.getUserId() == null) {
            log.info("[SseNotificationStrategy] Broadcasting system notification id={}", notification.getId());
            sseEmitterService.broadcast(notification);
        } else {
            log.info("[SseNotificationStrategy] Sending in-app notification id={} to userId={}",
                    notification.getId(), notification.getUserId());
            sseEmitterService.sendNotification(notification.getUserId(), notification);
        }
    }
}
