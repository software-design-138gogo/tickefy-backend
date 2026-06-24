package com.tickefy.csvingestion.modules.csvimport.client;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Infrastructure failure calling event-service (5xx / timeout / connect). Recorded by circuit breaker. */
public class EventUnavailableException extends ApiException {

    public EventUnavailableException(String message) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public EventUnavailableException(String message, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
