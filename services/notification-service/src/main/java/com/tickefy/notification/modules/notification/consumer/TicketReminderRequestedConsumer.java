package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.ProcessedMessageRepository;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
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

/**
 * Consumes {@code TicketReminderRequested} events from RabbitMQ.
 *
 * <p>On each message:
 *
 * <ol>
 *   <li>Saves an in-app {@link Notification} to the database.
 *   <li>Pushes SSE real-time notification to the user.
 *   <li>Sends a ticket reminder email.
 * </ol>
 *
 * <p>This consumer delegates all notification saving and channel delivery to the
 * {@link NotificationDispatcher} to adhere to the Strategy Pattern (OCP).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketReminderRequestedConsumer {

    private final NotificationDispatcher notificationDispatcher;
    private final ProcessedMessageRepository processedMessageRepository;

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

        // F1 dedup: atomic INSERT ... ON CONFLICT DO NOTHING. rows=0 -> already processed, skip.
        if (processedMessageRepository.tryMarkProcessed(envelope.getMessageId(), "TicketReminderRequested") == 0) {
            log.warn("[TicketReminderRequestedConsumer] Already processed messageId={} — skip", envelope.getMessageId());
            return;
        }

        // Build the in-app notification entity
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

        // Build the email parameters
        String emailSubject = "Tickefy — Nhắc nhở sự kiện sắp diễn ra";
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("concertTitle", payload.getConcertTitle());
        templateVars.put("eventDateTime", payload.getEventDateTime());
        templateVars.put("ticketCount", payload.getTicketCount());
        
        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";

        // Dispatch via strategy dispatcher
        NotificationContext context = NotificationContext.builder()
                .notification(notification)
                .emailSubject(emailSubject)
                .emailTemplateName("email/ticket-reminder")
                .templateVars(templateVars)
                .recipientEmail(recipientEmail)
                .build();

        notificationDispatcher.dispatch(context);
    }
}
