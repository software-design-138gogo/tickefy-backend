package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.modules.notification.service.EmailTemplateService;
import com.tickefy.notification.modules.notification.service.SseEmitterService;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaidConsumer {

    private final NotificationRepository notificationRepository;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final SseEmitterService sseEmitterService;

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

        // 1. Save in-app notification
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

        notificationRepository.save(notification);
        log.info(
                "[OrderPaidConsumer] Saved in-app notification userId={} orderId={}",
                payload.getUserId(),
                payload.getOrderId());

        // Push real-time SSE
        sseEmitterService.sendNotification(payload.getUserId(), notification);

        // 2. Send email (non-fatal)
        String emailSubject = "Tickefy — Xác nhận thanh toán đơn hàng";
        
        Map<String, Object> templateVars = new HashMap<>();
        templateVars.put("items", payload.getItems());
        templateVars.put("totalAmount", payload.getTotalAmount());
        templateVars.put("orderId", payload.getOrderId());
        
        String emailHtml = emailTemplateService.render("email/order-paid", templateVars);
        
        // In Phase 2 we send to a mock address since userId != email address.
        // Phase 3 will resolve user email via auth-service or include it in the event payload.
        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";
        emailService.sendEmail(recipientEmail, emailSubject, emailHtml);
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
