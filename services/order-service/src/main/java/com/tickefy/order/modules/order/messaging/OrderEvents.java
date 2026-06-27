package com.tickefy.order.modules.order.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Event message shapes for order-service AMQP.
 *
 * <p>All events use the standard ENVELOPE per backend-service-workflow §10:
 * {@code {messageId, eventType, eventVersion, <ts>, payload:{...domain}}}.
 * <ul>
 *   <li><b>order.*</b> (order.paid / order.payment.failed / order.expired) — consumed by inventory +
 *       e-ticket(Hòa). Envelope with {@code occurredAt} + nested {@code payload}.</li>
 *   <li><b>payment.*</b> (consumed by order; dev stub now, Payment/Dương later) — envelope with
 *       {@code timestamp} (per api-contracts §5, kept for Dương compat) + {@code eventVersion}.</li>
 * </ul>
 * {@code eventVersion="1.0"} on every event. Outbox payload is serialized as-is; consumers read
 * {@code payload.*}.
 */
public final class OrderEvents {

    private OrderEvents() {}

    public static final String EVENT_VERSION = "1.0";

    public static final class Type {
        public static final String ORDER_PAID = "OrderPaid";
        public static final String ORDER_PAYMENT_FAILED = "OrderPaymentFailed";
        public static final String ORDER_EXPIRED = "OrderExpired";

        private Type() {}
    }

    public static final class RoutingKey {
        public static final String ORDER_PAID = "order.paid";
        public static final String ORDER_PAYMENT_FAILED = "order.payment.failed";
        public static final String ORDER_EXPIRED = "order.expired";

        private RoutingKey() {}
    }

    // ── Inbound: payment.* (ENVELOPE — timestamp kept for Dương compat) ───────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentEnvelope(
            String messageId, String eventType, String eventVersion, String timestamp, PaymentPayload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentPayload(String orderId, String paymentTransactionId, String status) {}

    // ── Inbound: concert.cancelled (ENVELOPE from event-service, 7-field) ─────
    // Ignore source/occurredAt/correlationId/causationId — only concertId is needed (BC4).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConcertCancelledEnvelope(
            String messageId, String eventType, String eventVersion, ConcertCancelledPayload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConcertCancelledPayload(String concertId, String cancelledAt, String reason) {}

    // ── Outbound: order.paid (ENVELOPE) ──────────────────────────────────────
    public record OrderPaidMessage(
            String messageId,
            String eventType,
            String eventVersion,
            String occurredAt,
            OrderPaidPayload payload) {}

    public record OrderPaidPayload(
            String orderId,
            String userId,
            String concertId,
            String paidAt,
            List<OrderPaidItem> items) {}

    public record OrderPaidItem(
            String orderItemId,
            String ticketTypeId,
            int quantity,
            String zoneId,
            String ticketTypeName) {}

    // ── Outbound: order.payment.failed / order.expired (ENVELOPE) ─────────────
    public record OrderReleaseMessage(
            String messageId,
            String eventType,
            String eventVersion,
            String occurredAt,
            OrderReleasePayload payload) {}

    public record OrderReleasePayload(
            String orderId,
            String userId,
            List<OrderReleaseItem> items) {}

    public record OrderReleaseItem(String ticketTypeId, int quantity) {}
}
