package com.tickefy.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.tickefy.gateway.config.RateLimitProperties;
import com.tickefy.gateway.error.GatewayErrorWriter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

class RateLimitGlobalFilterTest {

  private RedisRateLimiter redisRateLimiter;
  private RateLimitKeyResolver keyResolver;
  private GatewayFilterChain chain;

  private SimpleMeterRegistry meterRegistry;
  private RateLimitGlobalFilter filter;

  @BeforeEach
  void setUp() {
    redisRateLimiter = mock(RedisRateLimiter.class);

    keyResolver = mock(RateLimitKeyResolver.class);

    chain = mock(GatewayFilterChain.class);

    RateLimitProperties properties = new RateLimitProperties();

    properties.setEnabled(true);
    properties.setRetryAfterSeconds(1);

    GatewayErrorWriter errorWriter = new GatewayErrorWriter(
        new ObjectMapper()
            .findAndRegisterModules());

    meterRegistry = new SimpleMeterRegistry();

    filter = new RateLimitGlobalFilter(
        redisRateLimiter,
        keyResolver,
        properties,
        errorWriter,
        meterRegistry);
  }

  @Test
  void shouldReturn429WhenPurchaseLimitIsExceeded() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .post("/api/orders")
            .build());

    RateLimiter.Response deniedResponse = mock(RateLimiter.Response.class);

    when(keyResolver.resolve(exchange))
        .thenReturn(
            Mono.just("user:test-user"));

    when(
        redisRateLimiter.isAllowed(
            "purchase",
            "user:test-user"))
        .thenReturn(
            Mono.just(deniedResponse));

    when(deniedResponse.isAllowed())
        .thenReturn(false);

    when(deniedResponse.getHeaders())
        .thenReturn(
            Map.of(
                RedisRateLimiter.REMAINING_HEADER,
                "0"));

    filter.filter(exchange, chain).block();

    assertThat(
        exchange.getResponse()
            .getStatusCode())
        .isEqualTo(
            HttpStatus.TOO_MANY_REQUESTS);

    assertThat(
        exchange.getResponse()
            .getHeaders()
            .getFirst("Retry-After"))
        .isEqualTo("1");

    assertThat(
        exchange.getResponse()
            .getBodyAsString()
            .block())
        .contains(
            "\"code\":\"RATE_LIMIT_EXCEEDED\"")
        .contains(
            "\"retryAfterSeconds\":1");

    verify(chain, never())
        .filter(any());
  }

  @Test
  void shouldForwardAllowedRequest() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .get("/api/concerts")
            .build());

    RateLimiter.Response allowedResponse = mock(RateLimiter.Response.class);

    when(keyResolver.resolve(exchange))
        .thenReturn(
            Mono.just("ip:127.0.0.1"));

    when(
        redisRateLimiter.isAllowed(
            "default",
            "ip:127.0.0.1"))
        .thenReturn(
            Mono.just(allowedResponse));

    when(allowedResponse.isAllowed())
        .thenReturn(true);

    when(allowedResponse.getHeaders())
        .thenReturn(
            Map.of(
                RedisRateLimiter.REMAINING_HEADER,
                "10"));

    when(chain.filter(exchange))
        .thenReturn(Mono.empty());

    filter.filter(exchange, chain).block();

    verify(chain).filter(exchange);

    assertThat(
        exchange.getResponse()
            .getHeaders()
            .getFirst(
                RedisRateLimiter.REMAINING_HEADER))
        .isEqualTo("10");
  }

  @Test
  void shouldFailOpenWhenRedisLimiterFails() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .post("/api/orders")
            .build());

    AtomicBoolean forwarded = new AtomicBoolean(false);

    when(keyResolver.resolve(exchange))
        .thenReturn(
            Mono.just("user:test-user"));

    when(
        redisRateLimiter.isAllowed(
            "purchase",
            "user:test-user"))
        .thenReturn(
            Mono.error(
                new RuntimeException(
                    "Redis unavailable")));

    when(chain.filter(exchange))
        .thenAnswer(invocation -> {
          forwarded.set(true);
          return Mono.empty();
        });

    filter.filter(exchange, chain).block();

    assertThat(forwarded).isTrue();

    double count = meterRegistry.get(
        "tickefy.gateway.rate.limit."
            + "fail.open")
        .tag(
            "policy",
            "purchase")
        .counter()
        .count();

    assertThat(count).isEqualTo(1.0);
  }

  @Test
  void shouldIncrementRejectedMetric() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .post("/api/orders")
            .build());

    RateLimiter.Response deniedResponse = mock(RateLimiter.Response.class);

    when(keyResolver.resolve(exchange))
        .thenReturn(
            Mono.just("user:test-user"));

    when(
        redisRateLimiter.isAllowed(
            "purchase",
            "user:test-user"))
        .thenReturn(
            Mono.just(deniedResponse));

    when(deniedResponse.isAllowed())
        .thenReturn(false);

    when(deniedResponse.getHeaders())
        .thenReturn(Map.of());

    filter.filter(exchange, chain).block();

    double count = meterRegistry.get(
        "tickefy.gateway.rate.limit."
            + "rejected")
        .tag(
            "policy",
            "purchase")
        .counter()
        .count();

    assertThat(count).isEqualTo(1.0);
  }
}