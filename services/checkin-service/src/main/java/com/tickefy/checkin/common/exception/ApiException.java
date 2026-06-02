package com.tickefy.checkin.common.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final Object details;

    public ApiException(ErrorCode errorCode, String message, HttpStatus status) {
        this(errorCode, message, status, null);
    }

    public ApiException(ErrorCode errorCode, String message, HttpStatus status, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Object getDetails() {
        return details;
    }
}
