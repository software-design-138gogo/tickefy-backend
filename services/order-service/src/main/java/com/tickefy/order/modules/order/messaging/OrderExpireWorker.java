package com.tickefy.order.modules.order.messaging;

import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.service.OrderPersistence;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expire worker (Pass 2): non-terminal orders past {@code expires_at} → EXPIRED + outbox OrderExpired
 * (drainer publishes {@code order.expired} → inventory releases). Sweeps BOTH PAYMENT_PENDING (payment
 * abandoned) and RESERVED (stuck when payment never started — e.g. payment service down); without the
 * latter a RESERVED order past expiry never leaves RESERVED and lingers as an orphan in my-orders. The
 * inventory release is idempotent (reservation status guard) and the ReservationReaper already returns
 * the seats via TTL, so re-releasing a RESERVED order is a safe no-op. Idempotent via markExpired guard.
 */
@Component
public class OrderExpireWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderExpireWorker.class);

    /** Non-terminal statuses that must expire once past {@code expires_at}. NEVER includes PAID. */
    private static final Set<String> EXPIRABLE_STATUSES =
            Set.of(OrderStatus.PAYMENT_PENDING.name(), OrderStatus.RESERVED.name());

    private final OrderRepository orderRepository;
    private final OrderPersistence orderPersistence;

    public OrderExpireWorker(OrderRepository orderRepository, OrderPersistence orderPersistence) {
        this.orderRepository = orderRepository;
        this.orderPersistence = orderPersistence;
    }

    @Scheduled(fixedDelayString = "${app.order.expire-poll-delay:5000}")
    public void sweep() {
        List<OrderEntity> expired = orderRepository.findByStatusInAndExpiresAtBefore(
                EXPIRABLE_STATUSES, Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        log.info("Expire worker: {} order(s) past expiry (PAYMENT_PENDING/RESERVED)", expired.size());
        for (OrderEntity order : expired) {
            try {
                orderPersistence.markExpired(order.getId());
            } catch (Exception e) {
                log.warn("Expire failed for orderId={} — will retry next sweep: {}", order.getId(), e.getMessage());
            }
        }
    }
}
