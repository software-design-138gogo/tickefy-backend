package com.tickefy.gateway.error;

import java.time.Instant;
import java.util.Map;

public record GatewayErrorResponse(
    boolean success,
    Object data,
    GatewayError error,
    String requestId,
    Instant timestamp) {

  public static GatewayErrorResponse of(
      int httpStatus,
      String code,
      String message,
      Map<String, Object> details,
      String requestId) {
    return new GatewayErrorResponse(
        false,
        null,
        new GatewayError(
            httpStatus,
            code,
            message,
            details),
        requestId,
        Instant.now());
  }

  public record GatewayError(
      int httpStatus,
      String code,
      String message,
      Map<String, Object> details) {
    public GatewayError {
      details = details == null
          ? Map.of()
          : Map.copyOf(details);
    }
  }
}