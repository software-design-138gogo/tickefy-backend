package com.tickefy.notification.modules.notification.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.NotificationOutbox;
import com.tickefy.notification.modules.core.repository.NotificationOutboxRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.modules.notification.service.EmailTemplateService;
import com.tickefy.notification.modules.outbox.dto.InternalEmailMessage;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailChannelConsumer {

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final NotificationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    private static final int MAX_RETRIES = 3;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_EMAIL_SEND, concurrency = "3-5")
    public void handleEmailSend(@Payload InternalEmailMessage message) {
        log.info("[EmailChannelConsumer] Received outbox message id={}", message.getOutboxId());

        NotificationOutbox outbox = outboxRepository.findById(message.getOutboxId()).orElse(null);
        if (outbox == null) {
            log.warn("[EmailChannelConsumer] Outbox record not found for id={}", message.getOutboxId());
            return;
        }

        try {
            Map<String, Object> payloadMap = objectMapper.readValue(outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            
            String emailSubject = (String) payloadMap.get("emailSubject");
            String emailTemplateName = (String) payloadMap.get("emailTemplateName");
            Map<String, Object> templateVars = (Map<String, Object>) payloadMap.get("templateVars");

            String emailHtml = emailTemplateService.render(emailTemplateName, templateVars);

            emailService.sendEmail(message.getRecipientEmail(), emailSubject, emailHtml);

            // Success
            outbox.setStatus(NotificationOutbox.OutboxStatus.SENT);
            outboxRepository.save(outbox);
            log.info("[EmailChannelConsumer] Successfully sent email and updated outbox id={}", message.getOutboxId());

        } catch (Exception e) {
            log.error("[EmailChannelConsumer] Failed to send email for outbox id={}", message.getOutboxId(), e);

            int retryCount = outbox.getRetryCount() + 1;
            if (retryCount >= MAX_RETRIES) {
                outbox.setStatus(NotificationOutbox.OutboxStatus.FAILED);
                outbox.setRetryCount(retryCount);
                outboxRepository.save(outbox);
                log.error("[EmailChannelConsumer] Max retries reached for outbox id={}. Marked as FAILED.", message.getOutboxId());
                
                // Throwing exception so RabbitMQ sends this to DLQ for manual inspection
                throw new RuntimeException("Max retries reached for email sending", e);
            } else {
                // Exponential backoff: 2s -> 4s -> 8s (e.g. 2^retryCount)
                int backoffSeconds = (int) Math.pow(2, retryCount);
                outbox.setStatus(NotificationOutbox.OutboxStatus.PENDING);
                outbox.setRetryCount(retryCount);
                outbox.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds));
                outboxRepository.save(outbox);
                log.info("[EmailChannelConsumer] Scheduled retry {} for outbox id={} in {} seconds", retryCount, message.getOutboxId(), backoffSeconds);
            }
        }
    }
}
