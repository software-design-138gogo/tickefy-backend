package com.tickefy.csvingestion.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final Object details;

    public ErrorResponse(String code, String message, Object details) {
        this.code = code;
        this.message = message;
        this.details = details;
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
