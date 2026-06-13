package com.tickefy.order.modules.order.statemachine;

import com.tickefy.order.common.exception.ApiException;
import com.tickefy.order.common.exception.ErrorCode;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            OrderStatus.CREATED, Set.of(OrderStatus.RESERVED, OrderStatus.CANCELLED),
            OrderStatus.RESERVED, Set.of(OrderStatus.PAYMENT_PENDING, OrderStatus.EXPIRED, OrderStatus.CANCELLED),
            OrderStatus.PAYMENT_PENDING, Set.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.EXPIRED),
            OrderStatus.PAID, Set.of(OrderStatus.REFUNDED),
            OrderStatus.PAYMENT_FAILED, Set.of(),
            OrderStatus.EXPIRED, Set.of(),
            OrderStatus.CANCELLED, Set.of(),
            OrderStatus.REFUNDED, Set.of());

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
