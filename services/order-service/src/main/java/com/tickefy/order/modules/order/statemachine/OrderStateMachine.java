package com.tickefy.order.modules.order.statemachine;

import com.tickefy.order.common.exception.ApiException;
import com.tickefy.order.common.exception.ErrorCode;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(OrderStatus.CREATED, Set.of(OrderStatus.RESERVED, OrderStatus.CANCELLED)),
            Map.entry(OrderStatus.RESERVED, Set.of(OrderStatus.PAYMENT_PENDING, OrderStatus.EXPIRED, OrderStatus.CANCELLED)),
            Map.entry(OrderStatus.PAYMENT_PENDING, Set.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.EXPIRED)),
            // Refund is two-phase: PAID never refunds directly, it must pass through REFUND_PENDING.
            Map.entry(OrderStatus.PAID, Set.of(OrderStatus.REFUND_PENDING)),
            Map.entry(OrderStatus.REFUND_PENDING, Set.of(OrderStatus.REFUNDED, OrderStatus.REFUND_MANUAL_REVIEW)),
            Map.entry(OrderStatus.PAYMENT_FAILED, Set.of()),
            Map.entry(OrderStatus.EXPIRED, Set.of()),
            Map.entry(OrderStatus.CANCELLED, Set.of()),
            Map.entry(OrderStatus.REFUNDED, Set.of()),
            Map.entry(OrderStatus.REFUND_MANUAL_REVIEW, Set.of()));

    public void assertTransition(OrderStatus from, OrderStatus to) {
        Set<OrderStatus> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "Invalid order transition " + from + " -> " + to,
                    HttpStatus.CONFLICT);
        }
    }
}
