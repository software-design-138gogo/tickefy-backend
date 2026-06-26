package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.TicketsIssuedPayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code TicketsIssued} events from RabbitMQ.
 *
 * <p>On each message:
 *
 * <ol>
 *   <li>Saves an in-app {@link Notification} to the database.
 *   <li>Sends an e-ticket email with ticket details via Mailpit (dev) or SMTP (prod).
 * </ol>
 *
 * <p>Email failures are non-fatal — in-app notifications are persisted regardless.
 *
 * <p>This consumer delegates all notification saving and channel delivery to the
 * {@link NotificationDispatcher} to adhere to the Strategy Pattern (OCP).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketsIssuedConsumer {

    private final NotificationDispatcher notificationDispatcher;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_TICKETS_ISSUED)
    @Transactional
    public void handle(@Payload EventEnvelope<TicketsIssuedPayload> envelope) {
        log.info(
                "[TicketsIssuedConsumer] Received messageId={} correlationId={}",
                envelope.getMessageId(),
                envelope.getCorrelationId());

        TicketsIssuedPayload payload = envelope.getPayload();
        if (payload == null || payload.getUserId() == null) {
            log.warn(
                    "[TicketsIssuedConsumer] Skipping malformed message messageId={}",
                    envelope.getMessageId());
            return;
        }

        int ticketCount = payload.getTickets() != null ? payload.getTickets().size() : 0;

        // Build the in-app notification entity
        String content = buildContent(payload, ticketCount);
        Notification notification =
                Notification.builder()
                        .userId(payload.getUserId())
                        .eventType("TicketsIssued")
                        .title("Vé của bạn đã sẵn sàng!")
                        .content(content)
                        .referenceId(
                                payload.getOrderId() != null
                                        ? payload.getOrderId().toString()
                                        : null)
                        .referenceType("ORDER")
                        .channel("IN_APP")
                        .build();

        // Build the email parameters
        String emailSubject = "Tickefy — Vé điện tử của bạn đã sẵn sàng";
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("ticketCount", ticketCount);
        templateVars.put("tickets", payload.getTickets());
        templateVars.put("orderId", payload.getOrderId());
        
        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";

        // Dispatch via strategy dispatcher
        NotificationContext context = NotificationContext.builder()
                .notification(notification)
                .emailSubject(emailSubject)
                .emailTemplateName("email/tickets-issued")
                .templateVars(templateVars)
                .recipientEmail(recipientEmail)
                .build();

        notificationDispatcher.dispatch(context);
    }

    private String buildContent(TicketsIssuedPayload payload, int ticketCount) {
        return String.format(
                "Đơn hàng #%s đã được xác nhận. %d vé của bạn đã được phát hành và sẵn sàng sử dụng.",
                payload.getOrderId(), ticketCount);
    }

    /** Returns the list of event types this consumer supports. */
    public static List<String> supportedEventTypes() {
        return List.of("TicketsIssued");
    }
}
