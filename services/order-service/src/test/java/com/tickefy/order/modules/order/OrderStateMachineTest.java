package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.order.common.exception.ApiException;
import com.tickefy.order.modules.order.statemachine.OrderStateMachine;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC9: State machine — valid transitions pass, illegal transitions throw ApiException 409 CONFLICT.
 * Pure unit test — no Spring context.
 */
class OrderStateMachineTest {

    private OrderStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new OrderStateMachine();
    }

    // ===== Valid transitions (Pass 1 saga path) =====

    @Test
    void validTransition_CREATED_to_RESERVED() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.CREATED, OrderStatus.RESERVED));
    }

    @Test
    void validTransition_CREATED_to_CANCELLED() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.CREATED, OrderStatus.CANCELLED));
    }

    @Test
    void validTransition_RESERVED_to_PAYMENT_PENDING() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.RESERVED, OrderStatus.PAYMENT_PENDING));
    }

    @Test
    void validTransition_RESERVED_to_CANCELLED() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.RESERVED, OrderStatus.CANCELLED));
    }

    @Test
    void validTransition_RESERVED_to_EXPIRED() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.RESERVED, OrderStatus.EXPIRED));
    }

    @Test
    void validTransition_PAYMENT_PENDING_to_PAID() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.PAYMENT_PENDING, OrderStatus.PAID));
    }

    @Test
    void validTransition_PAYMENT_PENDING_to_PAYMENT_FAILED() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_FAILED));
    }

    @Test
    void validTransition_PAYMENT_PENDING_to_EXPIRED() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.PAYMENT_PENDING, OrderStatus.EXPIRED));
    }

    // ===== Refund leg (two-phase: PAID -> REFUND_PENDING -> {REFUNDED, REFUND_MANUAL_REVIEW}) =====

    @Test
    void validTransition_PAID_to_REFUND_PENDING() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.PAID, OrderStatus.REFUND_PENDING));
    }

    @Test
    void validTransition_REFUND_PENDING_to_REFUNDED() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED));
    }

    @Test
    void validTransition_REFUND_PENDING_to_REFUND_MANUAL_REVIEW() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.assertTransition(OrderStatus.REFUND_PENDING, OrderStatus.REFUND_MANUAL_REVIEW));
    }

    @Test
    void illegalTransition_PAID_to_REFUNDED_direct_throws() {
        // Refund is two-phase now — PAID can no longer refund directly, must pass REFUND_PENDING.
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.PAID, OrderStatus.REFUNDED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_REFUND_PENDING_to_PAID_throws() {
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.REFUND_PENDING, OrderStatus.PAID))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_REFUND_MANUAL_REVIEW_to_REFUNDED_throws() {
        // Terminal in MVP — manual resolve is out of scope.
        assertThatThrownBy(() -> stateMachine.assertTransition(
                        OrderStatus.REFUND_MANUAL_REVIEW, OrderStatus.REFUNDED))
                .isInstanceOf(ApiException.class);
    }

    // ===== Illegal transitions: skip-step / back-step must throw =====

    @Test
    void illegalTransition_PAYMENT_PENDING_to_RESERVED_throws() {
        // Cannot jump backwards
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.PAYMENT_PENDING, OrderStatus.RESERVED))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void illegalTransition_CREATED_to_PAID_throws() {
        // Skip RESERVED + PAYMENT_PENDING — not allowed
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.CREATED, OrderStatus.PAID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void illegalTransition_PAID_to_RESERVED_throws() {
        // Cannot go back from PAID
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.PAID, OrderStatus.RESERVED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_PAID_to_CANCELLED_throws() {
        // PAID is point of no return — cannot cancel
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.PAID, OrderStatus.CANCELLED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_PAID_to_EXPIRED_throws() {
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.PAID, OrderStatus.EXPIRED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_CANCELLED_to_CREATED_throws() {
        // Terminal state — no exits
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.CANCELLED, OrderStatus.CREATED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_CANCELLED_to_RESERVED_throws() {
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.CANCELLED, OrderStatus.RESERVED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_PAYMENT_FAILED_to_PAYMENT_PENDING_throws() {
        // Terminal — no exits
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.PAYMENT_FAILED, OrderStatus.PAYMENT_PENDING))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_EXPIRED_to_PAYMENT_PENDING_throws() {
        // Terminal — no exits
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.EXPIRED, OrderStatus.PAYMENT_PENDING))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_REFUNDED_to_PAID_throws() {
        // Terminal — no exits
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.REFUNDED, OrderStatus.PAID))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void illegalTransition_RESERVED_to_CREATED_throws() {
        // Cannot go backwards
        assertThatThrownBy(() -> stateMachine.assertTransition(OrderStatus.RESERVED, OrderStatus.CREATED))
                .isInstanceOf(ApiException.class);
    }
}
