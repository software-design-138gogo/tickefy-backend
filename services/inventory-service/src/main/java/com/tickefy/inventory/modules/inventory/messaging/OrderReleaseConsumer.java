package com.tickefy.inventory.modules.inventory.messaging;

import com.tickefy.inventory.modules.inventory.service.ReservationLifecycleService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code order.payment.failed} + {@code order.expired} → release each item's reservation
 * (RESERVED → RELEASED, return stock + per-user quota). Idempotent via status guard. Poison → DLQ.
 */
@Component
public class OrderReleaseConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderReleaseConsumer.class);

    private final ReservationLifecycleService lifecycleService;

    public OrderReleaseConsumer(ReservationLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @RabbitListener(queues = RabbitMqConfig.ORDER_PAYMENT_FAILED_QUEUE)
    public void onOrderPaymentFailed(InventoryEvents.OrderReleaseMessage event) {
        release("order.payment.failed", event);
    }

    @RabbitListener(queues = RabbitMqConfig.ORDER_EXPIRED_QUEUE)
    public void onOrderExpired(InventoryEvents.OrderReleaseMessage event) {
        release("order.expired", event);
    }

    private void release(String source, InventoryEvents.OrderReleaseMessage event) {
        if (event == null || event.payload() == null
                || event.payload().orderId() == null || event.payload().items() == null) {
            throw new IllegalArgumentException(source + " missing payload.orderId/items");
        }
        InventoryEvents.OrderReleaseMessage.Payload payload = event.payload();
        UUID orderId = UUID.fromString(payload.orderId());
        log.info("Received {} messageId={} orderId={} items={}", source, event.messageId(), orderId, payload.items().size());
        for (InventoryEvents.OrderReleaseMessage.Item item : payload.items()) {
            lifecycleService.release(orderId, UUID.fromString(item.ticketTypeId()), item.quantity());
        }
    }
}
