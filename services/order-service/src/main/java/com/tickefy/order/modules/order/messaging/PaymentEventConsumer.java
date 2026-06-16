package com.tickefy.order.modules.order.messaging;

import com.tickefy.order.modules.order.service.OrderPersistence;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes payment outcome events (from Payment / dev stub) and drives the order state machine.
 *
 * <p>Idempotent via state guard in {@link OrderPersistence#markPaid}/{@code markPaymentFailed}
 * (already-terminal → no-op). Any other exception propagates → message dead-lettered (listener factory
 * has {@code setDefaultRequeueRejected(false)}), no infinite requeue.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final OrderPersistence orderPersistence;

    public PaymentEventConsumer(OrderPersistence orderPersistence) {
        this.orderPersistence = orderPersistence;
    }

    @RabbitListener(queues = RabbitMqConfig.PAYMENT_SUCCEEDED_QUEUE)
    public void onPaymentSucceeded(OrderEvents.PaymentEnvelope event) {
        UUID orderId = extractOrderId(event);
        log.info("Received payment.succeeded messageId={} orderId={}", safeMessageId(event), orderId);
        orderPersistence.markPaid(orderId, event.payload().paymentTransactionId());
    }

    @RabbitListener(queues = RabbitMqConfig.PAYMENT_FAILED_QUEUE)
    public void onPaymentFailed(OrderEvents.PaymentEnvelope event) {
        UUID orderId = extractOrderId(event);
        log.info("Received payment.failed messageId={} orderId={}", safeMessageId(event), orderId);
        orderPersistence.markPaymentFailed(orderId);
    }

    private UUID extractOrderId(OrderEvents.PaymentEnvelope event) {
        if (event == null || event.payload() == null || event.payload().orderId() == null) {
            throw new IllegalArgumentException("payment event missing payload.orderId");
        }
        return UUID.fromString(event.payload().orderId());
    }

    private String safeMessageId(OrderEvents.PaymentEnvelope event) {
        return event == null ? null : event.messageId();
    }
}
