package com.tickefy.notification.modules.notification.strategy;

import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationRepository notificationRepository;
    private final List<NotificationChannelStrategy> strategies;

    @Transactional
    public void dispatch(NotificationContext context) {
        Notification notification = context.getNotification();
        if (notification == null) {
            log.warn("[NotificationDispatcher] Notification entity is null, skipping save");
        } else {
            notificationRepository.save(notification);
            log.info("[NotificationDispatcher] Saved in-app notification id={}, userId={}, eventType={}",
                    notification.getId(), notification.getUserId(), notification.getEventType());
        }

        for (NotificationChannelStrategy strategy : strategies) {
            if (strategy.supports(context)) {
                try {
                    strategy.send(context);
                } catch (Exception e) {
                    log.error("[NotificationDispatcher] Failed to send notification via strategy={}",
                            strategy.getClass().getSimpleName(), e);
                }
            }
        }
    }
}
