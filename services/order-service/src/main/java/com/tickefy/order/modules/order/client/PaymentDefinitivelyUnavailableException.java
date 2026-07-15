package com.tickefy.order.modules.order.client;

/**
 * A DEFINITIVE payment-create failure: the request provably never reached the payment service
 * (connection refused), so no payment transaction could have been created. Unlike a plain
 * {@link PaymentUnavailableException} — which also covers AMBIGUOUS failures (read timeout, 5xx after
 * the request arrived) where a payment MAY have been created — a definitive failure is safe to
 * compensate: OrderService releases the held reservation immediately instead of leaving the order
 * RESERVED to hold seats until the expire worker's TTL.
 *
 * <p>It extends {@link PaymentUnavailableException} so every existing {@code catch} still maps it to a
 * 503; only the create path adds a more-specific catch that triggers the early release.
 */
public class PaymentDefinitivelyUnavailableException extends PaymentUnavailableException {

    public PaymentDefinitivelyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
