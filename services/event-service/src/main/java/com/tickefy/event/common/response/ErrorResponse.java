package com.tickefy.event.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final int httpStatus;
    private final String code;
    private final String message;
    private final Object details;

    public ErrorResponse(int httpStatus, String code, String message, Object details) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
        this.details = details;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Object getDetails() {
        return details;
    }
}
