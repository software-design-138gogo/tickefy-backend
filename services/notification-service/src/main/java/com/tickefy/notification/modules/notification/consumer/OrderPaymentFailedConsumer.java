package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
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
 * 
 * <p>This consumer delegates all notification saving and channel delivery to the
 * {@link NotificationDispatcher} to adhere to the Strategy Pattern (OCP).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentFailedConsumer {

    private final NotificationDispatcher notificationDispatcher;

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

        // Build the in-app notification entity
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

        // Build the email parameters
        String emailSubject = "Tickefy \u2014 Thanh toán thất bại";
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("orderId", payload.getOrderId());
        templateVars.put("reason", payload.getReason());
        templateVars.put("failedAt", payload.getFailedAt());

        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";

        // Dispatch via strategy dispatcher
        NotificationContext context = NotificationContext.builder()
                .notification(notification)
                .emailSubject(emailSubject)
                .emailTemplateName("email/order-payment-failed")
                .templateVars(templateVars)
                .recipientEmail(recipientEmail)
                .build();

        notificationDispatcher.dispatch(context);
    }
}
