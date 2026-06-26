package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
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
 *
 * <p>This consumer delegates all notification saving and channel delivery to the
 * {@link NotificationDispatcher} to adhere to the Strategy Pattern (OCP).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcertCancelledConsumer {

    private final NotificationDispatcher notificationDispatcher;

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

        // Build the system-wide in-app notification (userId = null = broadcast)
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

        // Build the email parameters
        String emailSubject = "Tickefy \u2014 Sự kiện bị hủy";
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("concertId", payload.getConcertId());
        templateVars.put("cancelledAt", payload.getCancelledAt());
        templateVars.put("reason", payload.getReason());

        String recipientEmail = "system-alerts@tickefy.local";

        // Dispatch via strategy dispatcher
        NotificationContext context = NotificationContext.builder()
                .notification(notification)
                .emailSubject(emailSubject)
                .emailTemplateName("email/concert-cancelled")
                .templateVars(templateVars)
                .recipientEmail(recipientEmail)
                .build();

        notificationDispatcher.dispatch(context);
    }
}
