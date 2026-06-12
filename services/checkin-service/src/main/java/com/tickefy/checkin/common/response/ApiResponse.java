package com.tickefy.checkin.common.response;

import java.time.Instant;

public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ErrorResponse error;
    private final String requestId;
    private final Instant timestamp;

    private ApiResponse(boolean success, T data, ErrorResponse error, String requestId) {
        this.success = success;
        this.data = data;
        this.error = error;
        this.requestId = requestId;
        this.timestamp = Instant.now();
    }

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId);
    }

    public static <T> ApiResponse<T> error(ErrorResponse error, String requestId) {
        return new ApiResponse<>(false, null, error, requestId);
    }

    public boolean isSuccess() { return success; }
    public T getData() { return data; }
    public ErrorResponse getError() { return error; }
    public String getRequestId() { return requestId; }
    public Instant getTimestamp() { return timestamp; }
}
