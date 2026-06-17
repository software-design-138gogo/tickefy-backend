package com.tickefy.gateway.filter;

import java.util.Map;
import java.util.Set;

import com.tickefy.gateway.config.RateLimitConfig;
import com.tickefy.gateway.config.RateLimitProperties;
import com.tickefy.gateway.error.GatewayErrorWriter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public final class RateLimitGlobalFilter
    implements GlobalFilter, Ordered {

  private static final Logger LOGGER = LoggerFactory.getLogger(
      RateLimitGlobalFilter.class);

  private static final Set<String> AUTH_PATHS = Set.of(
      "/api/auth/login",
      "/api/auth/register",
      "/api/auth/refresh-token");

  private final RedisRateLimiter redisRateLimiter;
  private final RateLimitKeyResolver keyResolver;
  private final RateLimitProperties properties;
  private final GatewayErrorWriter errorWriter;
  private final MeterRegistry meterRegistry;

  public RateLimitGlobalFilter(
      RedisRateLimiter redisRateLimiter,
      RateLimitKeyResolver keyResolver,
      RateLimitProperties properties,
      GatewayErrorWriter errorWriter,
      MeterRegistry meterRegistry) {
    this.redisRateLimiter = redisRateLimiter;
    this.keyResolver = keyResolver;
    this.properties = properties;
    this.errorWriter = errorWriter;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public Mono<Void> filter(
      ServerWebExchange exchange,
      GatewayFilterChain chain) {
    if (!properties.isEnabled()) {
      return chain.filter(exchange);
    }

    String policyId = resolvePolicyId(exchange.getRequest());

    return keyResolver.resolve(exchange)
        .flatMap(key -> checkRateLimit(
            exchange,
            policyId,
            key))
        .flatMap(rateLimitResponse -> {
          if (isFailOpen(rateLimitResponse)) {
            incrementMetric(
                "tickefy.gateway.rate.limit.fail.open",
                policyId);
          }

          copyRateLimitHeaders(
              exchange,
              rateLimitResponse);

          if (rateLimitResponse.isAllowed()) {
            return chain.filter(exchange);
          }

          incrementMetric(
              "tickefy.gateway.rate.limit.rejected",
              policyId);

          int retryAfterSeconds = properties.getRetryAfterSeconds();

          exchange.getResponse()
              .getHeaders()
              .set(
                  HttpHeaders.RETRY_AFTER,
                  String.valueOf(
                      retryAfterSeconds));

          return errorWriter.write(
              exchange,
              HttpStatus.TOO_MANY_REQUESTS,
              "RATE_LIMIT_EXCEEDED",
              "Too many requests.",
              Map.of(
                  "retryAfterSeconds",
                  retryAfterSeconds));
        });
  }

  private Mono<RateLimiter.Response> checkRateLimit(
      ServerWebExchange exchange,
      String policyId,
      String key) {
    return redisRateLimiter.isAllowed(
        policyId,
        key)
        .onErrorResume(exception -> {
          /*
           * Fail-open only for Redis/rate limiter failures.
           * Downstream routing errors must still propagate to the
           * gateway exception handler.
           */
          LOGGER.error(
              "Rate limiter unavailable; request allowed: "
                  + "requestId={}, policy={}, path={}",
              RequestContextWebFilter
                  .getRequestId(exchange),
              policyId,
              exchange.getRequest()
              .getPath()
                  .value(),
              exception);

          incrementMetric(
              "tickefy.gateway.rate.limit.fail.open",
              policyId);

          return Mono.just(
              new RateLimiter.Response(
                  true,
                  Map.of()));
        });
  }

  private boolean isFailOpen(
      RateLimiter.Response response) {
    String remaining = response.getHeaders().get(
        RedisRateLimiter.REMAINING_HEADER);

    return "-1".equals(remaining);
  }

  private void incrementMetric(
      String metricName,
      String policyId) {
    Counter.builder(metricName)
        .description(
            "Tickefy API Gateway rate limiting events")
        .tag(
            "policy",
            policyId)
        .register(meterRegistry)
        .increment();
  }

  private String resolvePolicyId(
      ServerHttpRequest request) {
    String path = request.getURI().getPath();

    if (HttpMethod.POST.equals(request.getMethod())
        && AUTH_PATHS.contains(path)) {
      return RateLimitConfig.AUTH_POLICY_ID;
    }

    if (HttpMethod.POST.equals(request.getMethod())
        && isCreateOrderPath(path)) {
      return RateLimitConfig.PURCHASE_POLICY_ID;
    }

    return RateLimitConfig.DEFAULT_POLICY_ID;
  }

  private boolean isCreateOrderPath(String path) {
    return "/api/orders".equals(path)
        || "/api/orders/".equals(path);
  }

  private void copyRateLimitHeaders(
      ServerWebExchange exchange,
      RateLimiter.Response response) {
    response.getHeaders()
        .forEach((name, value) -> {
          /*
           * RedisRateLimiter uses -1 when Redis fails
           * and the request is allowed via fail-open.
           */
          if (RedisRateLimiter.REMAINING_HEADER
              .equalsIgnoreCase(name)
              && "-1".equals(value)) {
            return;
          }

          exchange.getResponse()
              .getHeaders()
              .set(name, value);
        });
  }

  /**
   * Runs before the Gateway routing filters.
   * Spring Security has already completed authentication at the WebFilter layer.
   */
  @Override
  public int getOrder() {
    return -100;
  }
}
