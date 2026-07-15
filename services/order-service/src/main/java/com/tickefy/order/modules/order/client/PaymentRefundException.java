package com.tickefy.order.modules.order.client;

/** A non-retryable HTTP response from the payment refund endpoint. */
public class PaymentRefundException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public PaymentRefundException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
