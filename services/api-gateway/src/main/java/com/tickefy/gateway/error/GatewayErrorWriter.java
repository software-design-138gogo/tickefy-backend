package com.tickefy.gateway.error;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.tickefy.gateway.filter.RequestContextWebFilter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public final class GatewayErrorWriter {

  private final ObjectMapper objectMapper;

  public GatewayErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Mono<Void> write(
      ServerWebExchange exchange,
      HttpStatus status,
      String code,
      String message) {
    return write(
        exchange,
        status,
        code,
        message,
        Map.of());
  }

  public Mono<Void> write(
      ServerWebExchange exchange,
      HttpStatus status,
      String code,
      String message,
      Map<String, Object> details) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.empty();
    }

    String requestId = resolveRequestId(exchange);

    GatewayErrorResponse responseBody = GatewayErrorResponse.of(
        status.value(),
        code,
        message,
        details,
        requestId);

    byte[] responseBytes;

    try {
      responseBytes = objectMapper.writeValueAsBytes(responseBody);
    } catch (JsonProcessingException exception) {
      return Mono.error(exception);
    }

    exchange.getResponse().setStatusCode(status);

    exchange.getResponse()
        .getHeaders()
        .setContentType(MediaType.APPLICATION_JSON);

    exchange.getResponse()
        .getHeaders()
        .setCacheControl("no-store");

    exchange.getResponse()
        .getHeaders()
        .set(
            RequestContextWebFilter.REQUEST_ID_HEADER,
            requestId);

    DataBuffer buffer = exchange.getResponse()
        .bufferFactory()
        .wrap(responseBytes);

    return exchange.getResponse()
        .writeWith(Mono.just(buffer));
  }

  private String resolveRequestId(
      ServerWebExchange exchange) {
    String requestId = RequestContextWebFilter.getRequestId(exchange);

    if (requestId != null && !requestId.isBlank()) {
      return requestId;
    }

    String generatedRequestId = UUID.randomUUID().toString();

    exchange.getAttributes().put(
        RequestContextWebFilter.REQUEST_ID_ATTRIBUTE,
        generatedRequestId);

    return generatedRequestId;
  }
}