package com.tickefy.event.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ErrorResponse error;
    private final String requestId;

    private ApiResponse(boolean success, T data, ErrorResponse error, String requestId) {
        this.success = success;
        this.data = data;
        this.error = error;
        this.requestId = requestId;
    }

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId);
    }

    public static <T> ApiResponse<T> error(ErrorResponse error, String requestId) {
        return new ApiResponse<>(false, null, error, requestId);
    }

    public boolean isSuccess() {
        return success;
    }

    public T getData() {
        return data;
    }

    public ErrorResponse getError() {
        return error;
    }

    public String getRequestId() {
        return requestId;
    }
}
