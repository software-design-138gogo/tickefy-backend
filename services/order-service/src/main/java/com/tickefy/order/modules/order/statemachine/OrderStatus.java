package com.tickefy.order.modules.order.statemachine;

public enum OrderStatus {
    CREATED,
    RESERVED,
    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED,
    REFUND_PENDING,
    REFUND_MANUAL_REVIEW
}
