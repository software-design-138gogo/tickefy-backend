package com.tickefy.notification.modules.notification.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.notification.modules.core.entity.NotificationOutbox;
import com.tickefy.notification.modules.core.repository.NotificationOutboxRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationStrategy implements NotificationChannelStrategy {

    private final NotificationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(NotificationContext context) {
        return context.getEmailTemplateName() != null && context.getRecipientEmail() != null;
    }

    @Override
    public void send(NotificationContext context) {
        log.info("[EmailNotificationStrategy] Dispatching email to outbox for recipient={}", context.getRecipientEmail());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("emailSubject", context.getEmailSubject() != null ? context.getEmailSubject() : "Tickefy Notification");
        payloadMap.put("emailTemplateName", context.getEmailTemplateName());
        payloadMap.put("templateVars", context.getTemplateVars());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            log.error("[EmailNotificationStrategy] Failed to serialize email payload", e);
            throw new RuntimeException("Failed to serialize email payload", e);
        }

        NotificationOutbox outbox = NotificationOutbox.builder()
                .notificationId(context.getNotification() != null ? context.getNotification().getId() : null)
                .channel("EMAIL")
                .recipient(context.getRecipientEmail())
                .payload(payloadJson)
                .status(NotificationOutbox.OutboxStatus.PENDING)
                .retryCount(0)
                .build();

        outboxRepository.save(outbox);
        log.info("[EmailNotificationStrategy] Saved EMAIL outbox record for recipient={}", context.getRecipientEmail());
    }
}
