package com.tickefy.notification.modules.notification.consumer;

import com.tickefy.notification.config.RabbitMQConfig;
import com.tickefy.notification.modules.core.entity.Notification;
import com.tickefy.notification.modules.core.repository.NotificationRepository;
import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.TicketsIssuedPayload;
import com.tickefy.notification.shared.dto.TicketsIssuedPayload.TicketPayload;
import java.util.List;
import java.util.stream.Collectors;
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

        // 2. Send e-ticket email (non-fatal)
        String emailSubject = "Tickefy — Vé điện tử của bạn đã sẵn sàng";
        String emailHtml = buildEmailHtml(payload, ticketCount);
        String recipientEmail = "user+" + payload.getUserId() + "@mailpit.local";
        emailService.sendEmail(recipientEmail, emailSubject, emailHtml);
    }

    private String buildContent(TicketsIssuedPayload payload, int ticketCount) {
        return String.format(
                "Đơn hàng #%s đã được xác nhận. %d vé của bạn đã được phát hành và sẵn sàng sử dụng.",
                payload.getOrderId(), ticketCount);
    }

    private String buildEmailHtml(TicketsIssuedPayload payload, int ticketCount) {
        StringBuilder ticketRows = new StringBuilder();
        if (payload.getTickets() != null) {
            for (TicketPayload ticket : payload.getTickets()) {
                ticketRows.append(
                        String.format(
                                "<tr>"
                                        + "<td style='padding:8px;border-bottom:1px solid #eee'>%s</td>"
                                        + "<td style='padding:8px;border-bottom:1px solid #eee;text-align:center'>%s</td>"
                                        + "<td style='padding:8px;border-bottom:1px solid #eee;text-align:center'>%s</td>"
                                        + "</tr>",
                                ticket.getTicketTypeName() != null ? ticket.getTicketTypeName() : "N/A",
                                ticket.getTicketId() != null
                                        ? ticket.getTicketId().toString().substring(0, 8).toUpperCase() + "..."
                                        : "N/A",
                                ticket.getStatus() != null ? ticket.getStatus() : "ISSUED"));
            }
        }

        return """
                <!DOCTYPE html>
                <html lang="vi">
                <head><meta charset="UTF-8"><title>Vé điện tử Tickefy</title></head>
                <body style="font-family:Arial,sans-serif;background:#f5f5f5;margin:0;padding:20px">
                  <div style="max-width:600px;margin:0 auto;background:#fff;border-radius:8px;
                              padding:32px;box-shadow:0 2px 8px rgba(0,0,0,.08)">
                    <h2 style="color:#1a1a2e;margin-top:0">🎫 Vé của bạn đã sẵn sàng!</h2>
                    <p>Xin chào,</p>
                    <p>Chúng tôi vui mừng thông báo rằng <strong>"""
                + ticketCount
                + """
                     vé</strong> của bạn đã được phát hành thành công.</p>

                    <h3 style="color:#1a1a2e">Chi tiết vé:</h3>
                    <table style="width:100%;border-collapse:collapse">
                      <tr style="background:#f0f0f0">
                        <th style="padding:8px;text-align:left">Loại vé</th>
                        <th style="padding:8px;text-align:center">Mã vé</th>
                        <th style="padding:8px;text-align:center">Trạng thái</th>
                      </tr>
                """
                + ticketRows
                + """
                    </table>

                    <div style="background:#fff8e1;border-left:4px solid #ffc107;padding:12px 16px;
                                margin:20px 0;border-radius:4px">
                      <p style="margin:0;color:#856404">
                        📱 Vui lòng mang vé điện tử (QR code) khi tham dự sự kiện.
                        Vé có thể được xem trong ứng dụng Tickefy.
                      </p>
                    </div>

                    <p style="color:#666;font-size:12px">Mã đơn hàng: """
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

    /** Returns the list of event types this consumer supports. */
    public static List<String> supportedEventTypes() {
        return List.of("TicketsIssued");
    }
}
