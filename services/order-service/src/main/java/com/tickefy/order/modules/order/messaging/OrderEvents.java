package com.tickefy.order.modules.order.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Event message shapes for order-service AMQP.
 *
 * <p>Two conventions on purpose (driven by real counterparts, NOT a free choice):
 * <ul>
 *   <li><b>payment.*</b> (consumed by order; produced by dev stub now, by Payment/Dương later):
 *       nested ENVELOPE {@code {messageId,eventType,timestamp,payload}} per api-contracts §5 — so
 *       swapping the dev stub for Dương's real publisher needs no order change.</li>
 *   <li><b>order.paid</b> (consumed by e-ticket/Hòa LIVE + inventory): <b>FLAT</b> body — e-ticket's
 *       LIVE {@code @RabbitListener} deserializes the body directly into a flat record (no envelope).
 *       To not touch Hòa's code we publish flat, carrying {@code messageId}/{@code eventType} as
 *       top-level fields. ⚠️ Open question for team: align on the §5 envelope later.</li>
 * </ul>
 * order.payment.failed / order.expired (inventory-only) follow the same flat order.* style.
 */
public final class OrderEvents {

    private OrderEvents() {}

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

    // ── Inbound: payment.* (ENVELOPE) ────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentEnvelope(String messageId, String eventType, String timestamp, PaymentPayload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentPayload(String orderId, String paymentTransactionId, String status) {}

    // ── Outbound: order.paid (FLAT — Hòa compat) ─────────────────────────────
    public record OrderPaidMessage(
            String messageId,
            String eventType,
            String timestamp,
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

    // ── Outbound: order.payment.failed / order.expired (FLAT) ─────────────────
    public record OrderReleaseMessage(
            String messageId,
            String eventType,
            String timestamp,
            String orderId,
            String userId,
            List<OrderReleaseItem> items) {}

    public record OrderReleaseItem(String ticketTypeId, int quantity) {}
}
