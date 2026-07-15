package com.tickefy.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

class RequestContextWebFilterTest {

  private final RequestContextWebFilter filter = new RequestContextWebFilter();

  @Test
  void shouldPreserveValidRequestIdAndRemoveSpoofedIdentityHeaders() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/api/concerts")
            .header(
                RequestContextWebFilter.REQUEST_ID_HEADER,
                "client-request-123")
            .header(
                RequestContextWebFilter.USER_ID_HEADER,
                "fake-admin-id")
            .header(
                RequestContextWebFilter.USER_ROLES_HEADER,
                "ADMIN")
            .build());

    AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();

    WebFilterChain chain = currentExchange -> {
      forwardedExchange.set(currentExchange);
      return Mono.empty();
    };

    filter.filter(exchange, chain).block();

    ServerWebExchange forwarded = forwardedExchange.get();

    assertThat(forwarded).isNotNull();

    assertThat(
        forwarded.getRequest()
            .getHeaders()
            .getFirst(
                RequestContextWebFilter.REQUEST_ID_HEADER))
        .isEqualTo("client-request-123");

    assertThat(
        forwarded.getRequest()
            .getHeaders()
            .getFirst(
                RequestContextWebFilter.USER_ID_HEADER))
        .isNull();

    assertThat(
        forwarded.getRequest()
            .getHeaders()
            .getFirst(
                RequestContextWebFilter.USER_ROLES_HEADER))
        .isNull();

    assertThat(
        exchange.getResponse()
            .getHeaders()
            .getFirst(
                RequestContextWebFilter.REQUEST_ID_HEADER))
        .isEqualTo("client-request-123");
  }

  @Test
  void shouldGenerateUuidWhenRequestIdIsMissing() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/api/concerts")
            .build());

    AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();

    filter.filter(
        exchange,
        currentExchange -> {
          forwardedExchange.set(currentExchange);
          return Mono.empty();
        }).block();

    String generatedRequestId = forwardedExchange.get()
        .getRequest()
        .getHeaders()
        .getFirst(
            RequestContextWebFilter.REQUEST_ID_HEADER);

    assertThat(generatedRequestId).isNotBlank();

    assertThatCode(() -> UUID.fromString(generatedRequestId))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldReplaceUnsafeRequestId() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/api/concerts")
            .header(
                RequestContextWebFilter.REQUEST_ID_HEADER,
                "unsafe request id")
            .build());

    AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();

    filter.filter(
        exchange,
        currentExchange -> {
          forwardedExchange.set(currentExchange);
          return Mono.empty();
        }).block();

    String generatedRequestId = forwardedExchange.get()
        .getRequest()
        .getHeaders()
        .getFirst(
            RequestContextWebFilter.REQUEST_ID_HEADER);

    assertThat(generatedRequestId)
        .isNotEqualTo("unsafe request id");

    assertThatCode(() -> UUID.fromString(generatedRequestId))
        .doesNotThrowAnyException();
  }
}