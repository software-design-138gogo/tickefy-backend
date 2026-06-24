package com.tickefy.csvingestion.modules.csvimport.client;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Business outcome: concert not found in event-service. NOT recorded by circuit breaker. */
public class ConcertNotFoundException extends ApiException {

    public ConcertNotFoundException(String message) {
        super(ErrorCode.CONCERT_NOT_FOUND, message, HttpStatus.NOT_FOUND);
    }
}
