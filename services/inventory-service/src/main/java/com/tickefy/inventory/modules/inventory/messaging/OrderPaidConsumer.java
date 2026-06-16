package com.tickefy.inventory.modules.inventory.messaging;

import com.tickefy.inventory.modules.inventory.service.ReservationLifecycleService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code order.paid} → commit each item's reservation (RESERVED → COMMITTED).
 * Idempotent via reservation status guard. Poison → DLQ (factory requeue=false).
 */
@Component
public class OrderPaidConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidConsumer.class);

    private final ReservationLifecycleService lifecycleService;

    public OrderPaidConsumer(ReservationLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @RabbitListener(queues = RabbitMqConfig.ORDER_PAID_QUEUE)
    public void onOrderPaid(InventoryEvents.OrderPaidMessage event) {
        if (event == null || event.payload() == null
                || event.payload().orderId() == null || event.payload().items() == null) {
            throw new IllegalArgumentException("order.paid missing payload.orderId/items");
        }
        InventoryEvents.OrderPaidMessage.Payload payload = event.payload();
        UUID orderId = UUID.fromString(payload.orderId());
        log.info("Received order.paid messageId={} orderId={} items={}", event.messageId(), orderId, payload.items().size());
        for (InventoryEvents.OrderPaidMessage.Item item : payload.items()) {
            lifecycleService.commit(orderId, UUID.fromString(item.ticketTypeId()), item.quantity());
        }
    }
}
