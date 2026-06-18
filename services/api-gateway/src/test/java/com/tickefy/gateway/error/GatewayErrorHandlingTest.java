package com.tickefy.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.server.ResponseStatusException;

class GatewayErrorHandlingTest {

  private GatewayErrorWriter errorWriter;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules();

    errorWriter = new GatewayErrorWriter(objectMapper);
  }

  @Test
  void shouldReturnUnauthorizedEnvelope() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/api/orders")
            .header(
                "X-Request-ID",
                "request-401")
            .build());

    GatewayAuthenticationEntryPoint entryPoint = new GatewayAuthenticationEntryPoint(
        errorWriter);

    entryPoint.commence(
        exchange,
        new InsufficientAuthenticationException(
            "Authentication required"))
        .block();

    assertThat(
        exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    String body = exchange.getResponse()
        .getBodyAsString()
        .block();

    assertThat(body)
        .contains("\"success\":false")
        .contains("\"data\":null")
        .contains("\"code\":\"UNAUTHORIZED\"")
        .contains("\"requestId\":\"request-401\"");
  }

  @Test
  void shouldReturnForbiddenEnvelope() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/api/admin/concerts")
            .header(
                "X-Request-ID",
                "request-403")
            .build());

    GatewayAccessDeniedHandler handler = new GatewayAccessDeniedHandler(
        errorWriter);

    handler.handle(
        exchange,
        new AccessDeniedException("Forbidden")).block();

    assertThat(
        exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    assertThat(
        exchange.getResponse()
            .getBodyAsString()
            .block())
        .contains("\"code\":\"FORBIDDEN\"");
  }

  @Test
  void shouldReturnNotFoundEnvelope() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/unknown")
            .header(
                "X-Request-ID",
                "request-404")
            .build());

    GatewayWebExceptionHandler handler = new GatewayWebExceptionHandler(
        errorWriter);

    handler.handle(
        exchange,
        new ResponseStatusException(
            HttpStatus.NOT_FOUND))
        .block();

    assertThat(
        exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    assertThat(
        exchange.getResponse()
            .getBodyAsString()
            .block())
        .contains("\"code\":\"RESOURCE_NOT_FOUND\"");
  }

  @Test
  void shouldMapConnectionFailureToServiceUnavailable() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/api/concerts")
            .header(
                "X-Request-ID",
                "request-503")
            .build());

    GatewayWebExceptionHandler handler = new GatewayWebExceptionHandler(
        errorWriter);

    handler.handle(
        exchange,
        new ConnectException(
            "Connection refused"))
        .block();

    assertThat(
        exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

    assertThat(
        exchange.getResponse()
            .getBodyAsString()
            .block())
        .contains("\"code\":\"SERVICE_UNAVAILABLE\"");
  }
}