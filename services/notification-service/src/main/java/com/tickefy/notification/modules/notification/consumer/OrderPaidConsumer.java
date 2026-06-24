package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.OrderPaidPayload;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
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

        // 2. Send email (non-fatal)
        String emailSubject = "Tickefy — Xác nhận thanh toán đơn hàng";
        String emailHtml = buildOrderPaidEmailHtml(payload);
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

    private String buildOrderPaidEmailHtml(OrderPaidPayload payload) {
        NumberFormat vndFormat = NumberFormat.getInstance(new Locale("vi", "VN"));
        String amount =
                payload.getTotalAmount() != null
                        ? vndFormat.format(payload.getTotalAmount()) + " VND"
                        : "N/A";

        StringBuilder items = new StringBuilder();
        if (payload.getItems() != null) {
            for (OrderPaidPayload.OrderItemPayload item : payload.getItems()) {
                String unitPrice =
                        item.getUnitPrice() != null
                                ? vndFormat.format(item.getUnitPrice()) + " VND"
                                : "N/A";
                items.append(
                        String.format(
                                "<tr><td>%s</td><td style='text-align:center'>%d</td>"
                                        + "<td style='text-align:right'>%s</td></tr>",
                                item.getTicketTypeName() != null ? item.getTicketTypeName() : "N/A",
                                item.getQuantity() != null ? item.getQuantity() : 0,
                                unitPrice));
            }
        }

        return """
                <!DOCTYPE html>
                <html lang="vi">
                <head><meta charset="UTF-8"><title>Xác nhận thanh toán</title></head>
                <body style="font-family:Arial,sans-serif;background:#f5f5f5;margin:0;padding:20px">
                  <div style="max-width:600px;margin:0 auto;background:#fff;border-radius:8px;
                              padding:32px;box-shadow:0 2px 8px rgba(0,0,0,.08)">
                    <h2 style="color:#1a1a2e;margin-top:0">✅ Thanh toán thành công!</h2>
                    <p>Xin chào,</p>
                    <p>Đơn hàng của bạn đã được thanh toán thành công. Dưới đây là thông tin chi tiết:</p>

                    <table style="width:100%;border-collapse:collapse;margin:16px 0">
                      <tr style="background:#f0f0f0">
                        <th style="padding:8px;text-align:left">Loại vé</th>
                        <th style="padding:8px;text-align:center">Số lượng</th>
                        <th style="padding:8px;text-align:right">Đơn giá</th>
                      </tr>
                """
                + items
                + """
                    </table>

                    <p style="font-size:18px;font-weight:bold;color:#1a1a2e">
                      Tổng cộng: <span style="color:#e63946">"""
                + amount
                + """
                      </span>
                    </p>

                    <p style="color:#666;font-size:12px">
                      Mã đơn hàng: """
                + payload.getOrderId()
                + """
                    </p>
                    <hr style="border:none;border-top:1px solid #eee;margin:24px 0">
                    <p style="color:#999;font-size:11px;text-align:center">
                      © 2026 Tickefy. Email này được gửi tự động, vui lòng không trả lời.
                    </p>
                  </div>
                </body>
                </html>
                """;
    }

    /** Returns the list of event types this consumer supports. Used for version validation. */
    public static List<String> supportedEventTypes() {
        return List.of("OrderPaid");
    }
}
