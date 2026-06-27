package com.tickefy.order.modules.order.messaging;

import com.tickefy.order.modules.order.service.OrderPersistence;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code concert.cancelled} (from event-service) and moves every PAID order of the
 * cancelled concert to REFUND_PENDING so the refund worker (later phase) can process them.
 *
 * <p>Idempotent via the {@code status='PAID'} guard in the bulk update — redelivery / re-publish
 * flips 0 rows (orders already REFUND_PENDING are no longer PAID). No processed-message table is
 * needed (state guard, §6.9). A malformed message (missing payload.concertId) throws → the message
 * is dead-lettered (listener factory has {@code setDefaultRequeueRejected(false)}), never requeued.
 */
@Component
public class ConcertCancelledConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConcertCancelledConsumer.class);

    private final OrderPersistence orderPersistence;

    public ConcertCancelledConsumer(OrderPersistence orderPersistence) {
        this.orderPersistence = orderPersistence;
    }

    @RabbitListener(queues = RabbitMqConfig.CONCERT_CANCELLED_QUEUE)
    public void onConcertCancelled(OrderEvents.ConcertCancelledEnvelope event) {
        if (event == null || event.payload() == null || event.payload().concertId() == null) {
            throw new IllegalArgumentException("concert.cancelled missing payload.concertId");
        }
        UUID concertId = UUID.fromString(event.payload().concertId());
        int moved = orderPersistence.markConcertOrdersRefundPending(concertId);
        log.info("Received concert.cancelled messageId={} concertId={} ordersMovedToRefundPending={}",
                safeMessageId(event), concertId, moved);
    }

    private String safeMessageId(OrderEvents.ConcertCancelledEnvelope event) {
        return event == null ? null : event.messageId();
    }
}
