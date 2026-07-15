package com.tickefy.notification.modules.notification.strategy;

public interface NotificationChannelStrategy {
    boolean supports(NotificationContext context);
    void send(NotificationContext context);
}
