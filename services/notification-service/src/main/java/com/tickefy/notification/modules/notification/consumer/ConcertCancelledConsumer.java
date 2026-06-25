package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.modules.notification.service.EmailTemplateService;
import com.tickefy.notification.modules.notification.service.SseEmitterService;
import com.tickefy.notification.shared.dto.ConcertCancelledPayload;
import com.tickefy.notification.shared.dto.EventEnvelope;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code ConcertCancelled} events from RabbitMQ.
 *
 * <p>On each message:
 *
 * <ol>
 *   <li>Saves a system-wide broadcast in-app {@link Notification} to the database.
 *   <li>Broadcasts an SSE notification to all active users.
 *   <li>Sends a system alert email.
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcertCancelledConsumer {

    private final NotificationRepository notificationRepository;
    private final SseEmitterService sseEmitterService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CONCERT_CANCELLED)
    @Transactional
    public void handle(@Payload EventEnvelope<ConcertCancelledPayload> envelope) {
        log.info(
                "[ConcertCancelledConsumer] Received messageId={} correlationId={}",
                envelope.getMessageId(),
                envelope.getCorrelationId());

        ConcertCancelledPayload payload = envelope.getPayload();
        if (payload == null || payload.getConcertId() == null) {
            log.warn(
                    "[ConcertCancelledConsumer] Skipping malformed message messageId={}",
                    envelope.getMessageId());
            return;
        }

        // 1. Save system-wide in-app notification (userId = null = broadcast)
        Notification notification =
                Notification.builder()
                        .userId(null) // broadcast
                        .eventType("ConcertCancelled")
                        .title("Sự kiện đã bị hủy")
                        .content("Sự kiện #" + payload.getConcertId() + " đã bị hủy. Lý do: " + payload.getReason())
                        .referenceId(payload.getConcertId().toString())
                        .referenceType("CONCERT")
                        .channel("IN_APP")
                        .build();

        notificationRepository.save(notification);
        log.info("[ConcertCancelledConsumer] Saved broadcast notification for concertId={}", payload.getConcertId());

        // 2. Broadcast SSE to all connected users
        sseEmitterService.broadcast(notification);

        // 3. Send email system alert (non-fatal)
        String emailSubject = "Tickefy \u2014 Sự kiện bị hủy";

        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("concertId", payload.getConcertId());
        templateVars.put("cancelledAt", payload.getCancelledAt());
        templateVars.put("reason", payload.getReason());

        String emailHtml = emailTemplateService.render("email/concert-cancelled", templateVars);

        String recipientEmail = "system-alerts@tickefy.local";
        emailService.sendEmail(recipientEmail, emailSubject, emailHtml);
    }
}
