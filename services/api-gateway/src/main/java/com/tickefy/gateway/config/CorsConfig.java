package com.tickefy.gateway.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

  private static final List<String> ALLOWED_METHODS = List.of(
      HttpMethod.GET.name(),
      HttpMethod.POST.name(),
      HttpMethod.PUT.name(),
      HttpMethod.PATCH.name(),
      HttpMethod.DELETE.name(),
      HttpMethod.OPTIONS.name());

  private static final List<String> ALLOWED_HEADERS = List.of(
      HttpHeaders.AUTHORIZATION,
      HttpHeaders.CONTENT_TYPE,
      HttpHeaders.ACCEPT,
      "X-Request-ID",
      "Idempotency-Key");

  private static final List<String> EXPOSED_HEADERS = List.of(
      "X-Request-ID",
      HttpHeaders.RETRY_AFTER,
      HttpHeaders.LOCATION);

  @Bean
  UrlBasedCorsConfigurationSource corsConfigurationSource(
      CorsProperties properties) {
    List<String> allowedOrigins = resolveAllowedOrigins(
        properties);

    if (allowedOrigins.contains("*")) {
      throw new IllegalStateException(
          "CORS wildcard origin is not allowed "
              + "when credentials are enabled.");
    }

    CorsConfiguration configuration = new CorsConfiguration();

    configuration.setAllowedOrigins(allowedOrigins);

    configuration.setAllowedMethods(
        ALLOWED_METHODS);

    configuration.setAllowedHeaders(
        ALLOWED_HEADERS);

    configuration.setExposedHeaders(
        EXPOSED_HEADERS);

    configuration.setAllowCredentials(true);
    configuration.setMaxAge(properties.getMaxAgeSeconds());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

    source.registerCorsConfiguration(
        "/**",
        configuration);

    return source;
  }

  @Bean
  WebFilter corsPreflightWebFilter(
      CorsProperties properties) {
    return new CorsPreflightWebFilter(
        resolveAllowedOrigins(properties),
        properties.getMaxAgeSeconds());
  }

  private static List<String> resolveAllowedOrigins(
      CorsProperties properties) {
    return properties.getAllowedOrigins()
        .stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(origin -> !origin.isBlank())
        .distinct()
        .toList();
  }

  private static final class CorsPreflightWebFilter
      implements WebFilter, Ordered {

    private final List<String> allowedOrigins;

    private final long maxAgeSeconds;

    private CorsPreflightWebFilter(
        List<String> allowedOrigins,
        long maxAgeSeconds) {
      this.allowedOrigins = allowedOrigins;
      this.maxAgeSeconds = maxAgeSeconds;
    }

    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(
        ServerWebExchange exchange,
        WebFilterChain chain) {
      HttpHeaders requestHeaders = exchange.getRequest()
          .getHeaders();
      String origin = requestHeaders.getFirst(
          HttpHeaders.ORIGIN);
      String requestedMethod = requestHeaders.getFirst(
          HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
      List<String> requestedHeaders = requestHeaders
          .getAccessControlRequestHeaders();

      if (exchange.getRequest()
          .getMethod() != HttpMethod.OPTIONS
          || origin == null
          || requestedMethod == null) {
        return chain.filter(exchange);
      }

      addVaryHeaders(exchange.getResponse()
          .getHeaders());

      if (!allowedOrigins.contains(origin)
          || !ALLOWED_METHODS.contains(
              requestedMethod.toUpperCase(Locale.ROOT))
          || !areAllowedHeaders(requestedHeaders)) {
        exchange.getResponse()
            .setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse()
            .setComplete();
      }

      HttpHeaders responseHeaders = exchange.getResponse()
          .getHeaders();
      responseHeaders.setAccessControlAllowOrigin(origin);
      responseHeaders.setAccessControlAllowCredentials(true);
      responseHeaders.setAccessControlAllowMethods(
          ALLOWED_METHODS.stream()
              .map(HttpMethod::valueOf)
              .toList());
      responseHeaders.setAccessControlMaxAge(maxAgeSeconds);

      if (!requestedHeaders.isEmpty()) {
        responseHeaders.setAccessControlAllowHeaders(
            requestedHeaders);
      }

      return exchange.getResponse()
          .setComplete();
    }

    private boolean areAllowedHeaders(
        List<String> requestedHeaders) {
      return requestedHeaders.stream()
          .allMatch(this::isAllowedHeader);
    }

    private boolean isAllowedHeader(
        String requestedHeader) {
      return ALLOWED_HEADERS.stream()
          .anyMatch(allowedHeader -> allowedHeader.equalsIgnoreCase(
              requestedHeader));
    }

    private void addVaryHeaders(
        HttpHeaders responseHeaders) {
      responseHeaders.add(
          HttpHeaders.VARY,
          HttpHeaders.ORIGIN);
      responseHeaders.add(
          HttpHeaders.VARY,
          HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
      responseHeaders.add(
          HttpHeaders.VARY,
          HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
    }
  }
}
