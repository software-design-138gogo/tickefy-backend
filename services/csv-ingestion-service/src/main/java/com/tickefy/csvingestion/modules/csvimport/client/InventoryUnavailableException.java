package com.tickefy.csvingestion.modules.csvimport.client;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Infrastructure failure calling inventory-service (5xx / timeout / connect). Recorded by circuit breaker. */
public class InventoryUnavailableException extends ApiException {

    public InventoryUnavailableException(String message) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public InventoryUnavailableException(String message, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
