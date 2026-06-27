package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.ProcessedMessageRepository;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.OrderRefundedPayload;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code OrderRefunded} events from RabbitMQ (fixes F-new1).
 *
 * <p>On each message: dedups (F1), saves an in-app {@link Notification}, and dispatches a refund
 * confirmation email via the {@link NotificationDispatcher}. Email failures are non-fatal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRefundedConsumer {

    private static final String EVENT_TYPE = "OrderRefunded";

    private final NotificationDispatcher notificationDispatcher;
    private final ProcessedMessageRepository processedMessageRepository;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_REFUNDED)
    @Transactional
    public void handle(@Payload EventEnvelope<OrderRefundedPayload> envelope) {
        log.info(
                "[OrderRefundedConsumer] Received messageId={} correlationId={}",
                envelope.getMessageId(),
                envelope.getCorrelationId());

        OrderRefundedPayload payload = envelope.getPayload();
        if (payload == null || payload.getUserId() == null) {
            log.warn(
                    "[OrderRefundedConsumer] Skipping malformed message messageId={}",
                    envelope.getMessageId());
            return;
        }

        // F1 dedup: atomic INSERT ... ON CONFLICT DO NOTHING. rows=0 -> already processed, skip.
        if (processedMessageRepository.tryMarkProcessed(envelope.getMessageId(), EVENT_TYPE) == 0) {
            log.warn(
                    "[OrderRefundedConsumer] Already processed messageId={} — skip",
                    envelope.getMessageId());
            return;
        }

        // Build the in-app notification entity
        String amount =
                payload.getRefundAmount() != null
                        ? NumberFormat.getInstance(new Locale("vi", "VN"))
                                .format(payload.getRefundAmount())
                        : "N/A";
        String content =
                String.format(
                        "Đơn hàng #%s đã được hoàn tiền %s VND.",
                        payload.getOrderId(), amount);
        Notification notification =
                Notification.builder()
                        .userId(payload.getUserId())
                        .eventType(EVENT_TYPE)
                        .title("Hoàn tiền thành công")
                        .content(content)
                        .referenceId(
                                payload.getOrderId() != null
                                        ? payload.getOrderId().toString()
                                        : null)
                        .referenceType("ORDER")
                        .channel("IN_APP")
                        .build();

        // Build the email parameters (matches templates/email/order-refunded.html vars)
        String emailSubject = "Tickefy — Hoàn tiền đơn hàng";
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("orderId", payload.getOrderId());
        templateVars.put("refundAmount", payload.getRefundAmount());
        templateVars.put("currency", "VND");
        templateVars.put("refundedAt", payload.getRefundedAt());
        templateVars.put("reason", "Sự kiện bị hủy / hoàn tiền theo yêu cầu");

        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";

        NotificationContext context =
                NotificationContext.builder()
                        .notification(notification)
                        .emailSubject(emailSubject)
                        .emailTemplateName("email/order-refunded")
                        .templateVars(templateVars)
                        .recipientEmail(recipientEmail)
                        .build();

        notificationDispatcher.dispatch(context);
    }
}
