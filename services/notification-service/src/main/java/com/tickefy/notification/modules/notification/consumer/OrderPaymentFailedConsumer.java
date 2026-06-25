package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.modules.notification.service.EmailTemplateService;
import com.tickefy.notification.modules.notification.service.SseEmitterService;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.OrderPaymentFailedPayload;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code OrderPaymentFailed} events from RabbitMQ.
 *
 * <p>On each message:
 *
 * <ol>
 *   <li>Saves an in-app {@link Notification} to the database.
 *   <li>Pushes SSE real-time notification to the user.
 *   <li>Sends a payment failure warning email.
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentFailedConsumer {

    private final NotificationRepository notificationRepository;
    private final SseEmitterService sseEmitterService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PAYMENT_FAILED)
    @Transactional
    public void handle(@Payload EventEnvelope<OrderPaymentFailedPayload> envelope) {
        log.info(
                "[OrderPaymentFailedConsumer] Received messageId={} correlationId={}",
                envelope.getMessageId(),
                envelope.getCorrelationId());

        OrderPaymentFailedPayload payload = envelope.getPayload();
        if (payload == null || payload.getUserId() == null) {
            log.warn(
                    "[OrderPaymentFailedConsumer] Skipping malformed message messageId={}",
                    envelope.getMessageId());
            return;
        }

        // 1. Save in-app notification
        String content =
                String.format(
                        "Thanh toán cho đơn hàng #%s thất bại. Lý do: %s. Vui lòng thử lại.",
                        payload.getOrderId(), payload.getReason());
        Notification notification =
                Notification.builder()
                        .userId(payload.getUserId())
                        .eventType("OrderPaymentFailed")
                        .title("Thanh toán thất bại")
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
                "[OrderPaymentFailedConsumer] Saved in-app notification userId={} orderId={}",
                payload.getUserId(),
                payload.getOrderId());

        // 2. Push real-time SSE
        sseEmitterService.sendNotification(payload.getUserId(), notification);

        // 3. Send warning email (non-fatal)
        String emailSubject = "Tickefy \u2014 Thanh toán thất bại";

        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("orderId", payload.getOrderId());
        templateVars.put("reason", payload.getReason());
        templateVars.put("failedAt", payload.getFailedAt());

        String emailHtml = emailTemplateService.render("email/order-payment-failed", templateVars);

        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";
        emailService.sendEmail(recipientEmail, emailSubject, emailHtml);
    }
}
