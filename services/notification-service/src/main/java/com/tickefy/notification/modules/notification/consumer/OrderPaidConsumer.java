package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.OrderPaidPayload;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code OrderPaid} events from RabbitMQ.
 *
 * <p>On each message:
 *
 * <ol>
 *   <li>Saves an in-app {@link Notification} to the database.
 *   <li>Sends a payment confirmation email via Mailpit (dev) or SMTP (prod).
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
public class OrderPaidConsumer {

    private final NotificationDispatcher notificationDispatcher;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PAID)
    @Transactional
    public void handle(@Payload EventEnvelope<OrderPaidPayload> envelope) {
        log.info(
                "[OrderPaidConsumer] Received messageId={} correlationId={}",
                envelope.getMessageId(),
                envelope.getCorrelationId());

        OrderPaidPayload payload = envelope.getPayload();
        if (payload == null || payload.getUserId() == null) {
            log.warn(
                    "[OrderPaidConsumer] Skipping malformed message messageId={}",
                    envelope.getMessageId());
            return;
        }

        // Build the in-app notification entity
        String content = buildOrderPaidContent(payload);
        Notification notification =
                Notification.builder()
                        .userId(payload.getUserId())
                        .eventType("OrderPaid")
                        .title("Thanh toán thành công")
                        .content(content)
                        .referenceId(
                                payload.getOrderId() != null
                                        ? payload.getOrderId().toString()
                                        : null)
                        .referenceType("ORDER")
                        .channel("IN_APP")
                        .build();

        // Build the email parameters
        String emailSubject = "Tickefy — Xác nhận thanh toán đơn hàng";
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("items", payload.getItems());
        templateVars.put("totalAmount", payload.getTotalAmount());
        templateVars.put("orderId", payload.getOrderId());
        
        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";

        // Dispatch via strategy dispatcher
        NotificationContext context = NotificationContext.builder()
                .notification(notification)
                .emailSubject(emailSubject)
                .emailTemplateName("email/order-paid")
                .templateVars(templateVars)
                .recipientEmail(recipientEmail)
                .build();

        notificationDispatcher.dispatch(context);
    }

    private String buildOrderPaidContent(OrderPaidPayload payload) {
        NumberFormat vndFormat = NumberFormat.getInstance(new Locale("vi", "VN"));
        String amount =
                payload.getTotalAmount() != null
                        ? vndFormat.format(payload.getTotalAmount()) + " VND"
                        : "N/A";
        return String.format(
                "Đơn hàng #%s đã được thanh toán thành công. Tổng tiền: %s.",
                payload.getOrderId(), amount);
    }

    /** Returns the list of event types this consumer supports. Used for version validation. */
    public static List<String> supportedEventTypes() {
        return List.of("OrderPaid");
    }
}
