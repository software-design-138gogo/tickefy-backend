package com.tickefy.order.modules.order.messaging;

import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.service.OrderPersistence;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expire worker (Pass 2): PAYMENT_PENDING orders past {@code expires_at} → EXPIRED + outbox OrderExpired
 * (drainer publishes {@code order.expired} → inventory releases). Idempotent via markExpired state guard.
 */
@Component
public class OrderExpireWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderExpireWorker.class);

    private final OrderRepository orderRepository;
    private final OrderPersistence orderPersistence;

    public OrderExpireWorker(OrderRepository orderRepository, OrderPersistence orderPersistence) {
        this.orderRepository = orderRepository;
        this.orderPersistence = orderPersistence;
    }

    @Scheduled(fixedDelayString = "${app.order.expire-poll-delay:5000}")
    public void sweep() {
        List<OrderEntity> expired = orderRepository.findByStatusAndExpiresAtBefore(
                OrderStatus.PAYMENT_PENDING.name(), Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        log.info("Expire worker: {} PAYMENT_PENDING order(s) past expiry", expired.size());
        for (OrderEntity order : expired) {
            try {
                orderPersistence.markExpired(order.getId());
            } catch (Exception e) {
                log.warn("Expire failed for orderId={} — will retry next sweep: {}", order.getId(), e.getMessage());
            }
        }
    }
}
