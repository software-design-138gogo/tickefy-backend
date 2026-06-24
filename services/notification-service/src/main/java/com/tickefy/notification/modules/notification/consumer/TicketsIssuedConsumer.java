package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.modules.notification.service.EmailTemplateService;
import com.tickefy.notification.modules.notification.service.SseEmitterService;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.TicketsIssuedPayload;
import com.tickefy.notification.shared.dto.TicketsIssuedPayload.TicketPayload;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketsIssuedConsumer {

    private final NotificationRepository notificationRepository;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final SseEmitterService sseEmitterService;

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

        // 1. Save in-app notification
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

        notificationRepository.save(notification);
        log.info(
                "[TicketsIssuedConsumer] Saved in-app notification userId={} orderId={} ticketCount={}",
                payload.getUserId(),
                payload.getOrderId(),
                ticketCount);

        // Push real-time SSE
        sseEmitterService.sendNotification(payload.getUserId(), notification);

        // 2. Send e-ticket email (non-fatal)
        String emailSubject = "Tickefy — Vé điện tử của bạn đã sẵn sàng";
        
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("ticketCount", ticketCount);
        templateVars.put("tickets", payload.getTickets());
        templateVars.put("orderId", payload.getOrderId());
        
        String emailHtml = emailTemplateService.render("email/tickets-issued", templateVars);
        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";
        emailService.sendEmail(recipientEmail, emailSubject, emailHtml);
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
