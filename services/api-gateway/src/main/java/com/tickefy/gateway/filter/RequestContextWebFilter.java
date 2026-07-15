package com.tickefy.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
public final class RequestContextWebFilter implements WebFilter, Ordered {

  public static final String REQUEST_ID_HEADER = "X-Request-ID";
  public static final String USER_ID_HEADER = "X-User-ID";
  public static final String USER_ROLES_HEADER = "X-User-Roles";

  /**
   * Allow other components in Gateway to get request ID without
   * reading header again
   */
  public static final String REQUEST_ID_ATTRIBUTE = RequestContextWebFilter.class.getName() + ".requestId";

  /**
   * Used for reactive logging/tracing at the next observability step.
   */
  public static final String REQUEST_ID_CONTEXT_KEY = "requestId";

  /**
   * Accept UUID or ID from tracing system, for example:
   *
   * 550e8400-e29b-41d4-a716-446655440000
   * req-550e8400-e29b-41d4-a716-446655440000
   * frontend.request_001
   *
   * Limit 100 characters, no whitespace or dangerous header characters allowed.
   */
  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$");

  @Override
  public Mono<Void> filter(
      ServerWebExchange exchange,
      WebFilterChain chain) {
    String requestId = resolveRequestId(
        exchange.getRequest()
            .getHeaders()
            .getFirst(REQUEST_ID_HEADER));

    ServerHttpRequest sanitizedRequest = exchange.getRequest()
        .mutate()
        .headers(headers -> {
          // Always overwrite with the validated ID
          headers.set(REQUEST_ID_HEADER, requestId);

          // Do not trust identity headers from client
          headers.remove(USER_ID_HEADER);
          headers.remove(USER_ROLES_HEADER);
        })
        .build();

    ServerWebExchange sanitizedExchange = exchange.mutate()
        .request(sanitizedRequest)
        .build();

    sanitizedExchange.getAttributes()
        .put(REQUEST_ID_ATTRIBUTE, requestId);

    /*
     * Set early so responses created by Spring Security also have X-Request-ID
     */
    sanitizedExchange.getResponse()
        .getHeaders()
        .set(REQUEST_ID_HEADER, requestId);

    /*
     * Set once more before commit to prevent downstream response from overwriting
     * with another request ID
     */
    sanitizedExchange.getResponse().beforeCommit(() -> {
      sanitizedExchange.getResponse()
          .getHeaders()
          .set(REQUEST_ID_HEADER, requestId);

      return Mono.empty();
    });

    return chain.filter(sanitizedExchange)
        .contextWrite(context -> context.put(REQUEST_ID_CONTEXT_KEY, requestId));
  }

  private String resolveRequestId(String candidate) {
    if (candidate != null) {
      String normalized = candidate.trim();

      if (SAFE_REQUEST_ID.matcher(normalized).matches()) {
        return normalized;
      }
    }

    return UUID.randomUUID().toString();
  }

  /**
   * Run before Spring Security so that all responses,
   * including 401 created by Security, have request ID.
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  public static String getRequestId(ServerWebExchange exchange) {
    Object value = exchange.getAttribute(REQUEST_ID_ATTRIBUTE);

    if (value instanceof String requestId) {
      return requestId;
    }

    return exchange.getRequest()
        .getHeaders()
        .getFirst(REQUEST_ID_HEADER);
  }
}