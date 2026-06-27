package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.ProcessedMessageRepository;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.OrderExpiredPayload;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code OrderExpired} events from RabbitMQ (fixes F-new5).
 *
 * <p>On each message: dedups (F1), saves an in-app {@link Notification}, and dispatches an
 * order-expired email via the {@link NotificationDispatcher}. Email failures are non-fatal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiredConsumer {

    private static final String EVENT_TYPE = "OrderExpired";

    private final NotificationDispatcher notificationDispatcher;
    private final ProcessedMessageRepository processedMessageRepository;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_EXPIRED)
    @Transactional
    public void handle(@Payload EventEnvelope<OrderExpiredPayload> envelope) {
        log.info(
                "[OrderExpiredConsumer] Received messageId={} correlationId={}",
                envelope.getMessageId(),
                envelope.getCorrelationId());

        OrderExpiredPayload payload = envelope.getPayload();
        if (payload == null || payload.getUserId() == null) {
            log.warn(
                    "[OrderExpiredConsumer] Skipping malformed message messageId={}",
                    envelope.getMessageId());
            return;
        }

        // F1 dedup: atomic INSERT ... ON CONFLICT DO NOTHING. rows=0 -> already processed, skip.
        if (processedMessageRepository.tryMarkProcessed(envelope.getMessageId(), EVENT_TYPE) == 0) {
            log.warn(
                    "[OrderExpiredConsumer] Already processed messageId={} — skip",
                    envelope.getMessageId());
            return;
        }

        // Build the in-app notification entity
        String content =
                String.format(
                        "Đơn hàng #%s đã hết hạn do chưa hoàn tất thanh toán. Vé đã được trả lại kho.",
                        payload.getOrderId());
        Notification notification =
                Notification.builder()
                        .userId(payload.getUserId())
                        .eventType(EVENT_TYPE)
                        .title("Đơn hàng đã hết hạn")
                        .content(content)
                        .referenceId(
                                payload.getOrderId() != null
                                        ? payload.getOrderId().toString()
                                        : null)
                        .referenceType("ORDER")
                        .channel("IN_APP")
                        .build();

        // Build the email parameters (matches templates/email/order-expired.html vars)
        String emailSubject = "Tickefy — Đơn hàng đã hết hạn";
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("orderId", payload.getOrderId());

        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";

        NotificationContext context =
                NotificationContext.builder()
                        .notification(notification)
                        .emailSubject(emailSubject)
                        .emailTemplateName("email/order-expired")
                        .templateVars(templateVars)
                        .recipientEmail(recipientEmail)
                        .build();

        notificationDispatcher.dispatch(context);
    }
}
