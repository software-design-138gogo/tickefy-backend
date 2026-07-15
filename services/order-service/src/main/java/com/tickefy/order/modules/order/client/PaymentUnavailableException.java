package com.tickefy.order.modules.order.client;

/**
 * Thrown when Payment service is unreachable (5xx / timeout / connect error).
 * OrderService KEEPS the order in RESERVED state and returns 503 to caller.
 */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message) {
        super(message);
    }

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
