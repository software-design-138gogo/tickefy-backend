package com.tickefy.eticket.common.response;

import java.util.Collections;

public class ErrorResponse {

    private final String code;
    private final String message;
    private final Object details;

    public ErrorResponse(String code, String message, Object details) {
        this.code = code;
        this.message = message;
        this.details = details != null ? details : Collections.emptyMap();
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
