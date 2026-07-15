package com.tickefy.notification.modules.notification.strategy;

import com.tickefy.notification.modules.core.entity.Notification;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NotificationContext {
    private final Notification notification;
    private final String emailSubject;
    private final String emailTemplateName;
    private final Map<String, Object> templateVars;
    private final String recipientEmail;
}
