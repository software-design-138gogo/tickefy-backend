package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.order.BaseIntegrationTest;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.messaging.OrderExpireWorker;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the expire worker sweeps BOTH RESERVED and PAYMENT_PENDING past {@code expires_at}, while
 * leaving future-dated orders and already-PAID orders untouched (a PAID order must never be expired).
 */
class OrderExpireWorkerTest extends BaseIntegrationTest {

    @Autowired private OrderExpireWorker worker;
    @Autowired private OrderRepository orderRepository;

    private OrderEntity persist(String status, Instant expiresAt) {
        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .concertId(UUID.randomUUID())
                .status(status)
                .idempotencyKey("idem-" + UUID.randomUUID())
                .totalAmount(50_000L)
                .expiresAt(expiresAt)
                .build();
        return orderRepository.saveAndFlush(order);
    }

    private String statusOf(UUID id) {
        return orderRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void sweep_expiresReservedAndPaymentPendingPastExpiry_leavesFutureAndPaidUntouched() {
        Instant past = Instant.now().minusSeconds(3600);
        Instant future = Instant.now().plusSeconds(3600);

        OrderEntity reservedPast = persist(OrderStatus.RESERVED.name(), past);
        OrderEntity reservedFuture = persist(OrderStatus.RESERVED.name(), future);
        OrderEntity pendingPast = persist(OrderStatus.PAYMENT_PENDING.name(), past);
        OrderEntity paidPast = persist(OrderStatus.PAID.name(), past);

        worker.sweep();

        // RESERVED past expiry -> EXPIRED (the bug this fixes).
        assertThat(statusOf(reservedPast.getId())).isEqualTo(OrderStatus.EXPIRED.name());
        // PAYMENT_PENDING past expiry -> EXPIRED (no regression).
        assertThat(statusOf(pendingPast.getId())).isEqualTo(OrderStatus.EXPIRED.name());
        // RESERVED not yet past expiry -> still RESERVED (no early expiry).
        assertThat(statusOf(reservedFuture.getId())).isEqualTo(OrderStatus.RESERVED.name());
        // PAID past expiry -> still PAID (a paid order must never be expired).
        assertThat(statusOf(paidPast.getId())).isEqualTo(OrderStatus.PAID.name());
    }
}
