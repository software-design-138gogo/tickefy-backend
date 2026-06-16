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
        if (event == null || event.orderId() == null || event.items() == null) {
            throw new IllegalArgumentException("order.paid missing orderId/items");
        }
        UUID orderId = UUID.fromString(event.orderId());
        log.info("Received order.paid messageId={} orderId={} items={}", event.messageId(), orderId, event.items().size());
        for (InventoryEvents.OrderPaidMessage.Item item : event.items()) {
            lifecycleService.commit(orderId, UUID.fromString(item.ticketTypeId()), item.quantity());
        }
    }
}
