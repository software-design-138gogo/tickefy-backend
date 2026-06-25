package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.modules.notification.service.EmailTemplateService;
import com.tickefy.notification.modules.notification.service.SseEmitterService;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.TicketReminderRequestedPayload;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketReminderRequestedConsumer {

    private final NotificationRepository notificationRepository;
    private final SseEmitterService sseEmitterService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_TICKET_REMINDER_REQUESTED)
    @Transactional
    public void handle(@Payload EventEnvelope<TicketReminderRequestedPayload> envelope) {
        log.info("[TicketReminderRequestedConsumer] Received messageId={} correlationId={}",
                envelope.getMessageId(), envelope.getCorrelationId());

        TicketReminderRequestedPayload payload = envelope.getPayload();
        if (payload == null || payload.getUserId() == null || payload.getConcertId() == null) {
            log.warn("[TicketReminderRequestedConsumer] Skipping malformed message messageId={}", envelope.getMessageId());
            return;
        }

        // 1. Save in-app notification
        String content = String.format("Chỉ còn chưa đầy 24h nữa là sự kiện %s sẽ chính thức bắt đầu! Hãy chuẩn bị sẵn sàng %d vé của bạn.",
                payload.getConcertTitle(), payload.getTicketCount());
        
        Notification notification = Notification.builder()
                .userId(payload.getUserId())
                .eventType("TicketReminderRequested")
                .title("Sự kiện sắp diễn ra")
                .content(content)
                .referenceId(payload.getConcertId().toString())
                .referenceType("CONCERT")
                .channel("IN_APP")
                .build();
        
        notificationRepository.save(notification);
        log.info("[TicketReminderRequestedConsumer] Saved in-app notification userId={} concertId={}",
                payload.getUserId(), payload.getConcertId());

        // 2. Push SSE
        sseEmitterService.sendNotification(payload.getUserId(), notification);

        // 3. Send email
        Map<String, Object> vars = new HashMap<>();
        vars.put("concertTitle", payload.getConcertTitle());
        vars.put("eventDateTime", payload.getEventDateTime());
        vars.put("ticketCount", payload.getTicketCount());
        
        String html = emailTemplateService.render("email/ticket-reminder", vars);
        
        // Use Mailpit format for user
        String recipient = "user+" + payload.getUserId() + "@mailpit.local";
        emailService.sendEmail(recipient, "Tickefy — Nhắc nhở sự kiện sắp diễn ra", html);
    }
}
