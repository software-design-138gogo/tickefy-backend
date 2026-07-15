package com.tickefy.order.modules.order.client;

import com.tickefy.order.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Thrown when Inventory returns a business error (409/422/403).
 * OrderService CANCELS the order and re-throws as the appropriate ApiException.
 */
public class InventoryBusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus httpStatus;
    private final Object details;

    public InventoryBusinessException(ErrorCode errorCode, String message, HttpStatus httpStatus, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Object getDetails() {
        return details;
    }
}
