package com.tickefy.gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;

import com.tickefy.gateway.filter.RequestContextWebFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;

@Component
@Order(-2)
public final class GatewayWebExceptionHandler
    implements WebExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(
      GatewayWebExceptionHandler.class);

  private final GatewayErrorWriter errorWriter;

  public GatewayWebExceptionHandler(
      GatewayErrorWriter errorWriter) {
    this.errorWriter = errorWriter;
  }

  @Override
  public Mono<Void> handle(
      ServerWebExchange exchange,
      Throwable exception) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(exception);
    }

    ErrorDescriptor descriptor = classify(exception);

    String requestId = RequestContextWebFilter.getRequestId(exchange);

    if (descriptor.status().is5xxServerError()) {
      LOGGER.error(
          "Gateway request failed: requestId={}, method={}, path={}, "
              + "status={}, exceptionType={}",
          requestId,
          exchange.getRequest().getMethod(),
          exchange.getRequest().getPath().value(),
          descriptor.status().value(),
          exception.getClass().getName(),
          exception);
    } else {
      LOGGER.debug(
          "Gateway request rejected: requestId={}, method={}, "
              + "path={}, status={}, exceptionType={}",
          requestId,
          exchange.getRequest().getMethod(),
          exchange.getRequest().getPath().value(),
          descriptor.status().value(),
          exception.getClass().getName());
    }

    return errorWriter.write(
        exchange,
        descriptor.status(),
        descriptor.code(),
        descriptor.message());
  }

  private ErrorDescriptor classify(
      Throwable exception) {
    ResponseStatusException statusException = findCause(
        exception,
        ResponseStatusException.class);

    if (statusException != null) {
      return fromHttpStatus(
          statusException
              .getStatusCode()
              .value());
    }

    if (hasDependencyFailureCause(exception)) {
      return new ErrorDescriptor(
          HttpStatus.SERVICE_UNAVAILABLE,
          "SERVICE_UNAVAILABLE",
          "Service is temporarily unavailable.");
    }

    return new ErrorDescriptor(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_SERVER_ERROR",
        "Internal server error.");
  }

  private ErrorDescriptor fromHttpStatus(
      int statusCode) {
    return switch (statusCode) {
      case 400 -> new ErrorDescriptor(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_ERROR",
          "Invalid request data.");

      case 401 -> new ErrorDescriptor(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHORIZED",
          "Authentication required.");

      case 403 -> new ErrorDescriptor(
          HttpStatus.FORBIDDEN,
          "FORBIDDEN",
          "Access denied.");

      case 404 -> new ErrorDescriptor(
          HttpStatus.NOT_FOUND,
          "RESOURCE_NOT_FOUND",
          "Resource not found.");

      case 429 -> new ErrorDescriptor(
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMIT_EXCEEDED",
          "Too many requests.");

      case 502, 503, 504 -> new ErrorDescriptor(
          HttpStatus.SERVICE_UNAVAILABLE,
          "SERVICE_UNAVAILABLE",
          "Service is temporarily unavailable.");

      default -> new ErrorDescriptor(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "INTERNAL_SERVER_ERROR",
          "Internal server error.");
    };
  }

  private boolean hasDependencyFailureCause(
      Throwable exception) {
    Throwable current = exception;

    while (current != null) {
      if (current instanceof ConnectException
          || current instanceof UnknownHostException
          || current instanceof TimeoutException
          || current instanceof ConnectTimeoutException
          || current instanceof ReadTimeoutException) {
        return true;
      }

      current = current.getCause();
    }

    return false;
  }

  private <T extends Throwable> T findCause(
      Throwable exception,
      Class<T> expectedType) {
    Throwable current = exception;

    while (current != null) {
      if (expectedType.isInstance(current)) {
        return expectedType.cast(current);
      }

      current = current.getCause();
    }

    return null;
  }

  private record ErrorDescriptor(
      HttpStatus status,
      String code,
      String message) {
  }
}
