package com.tickefy.gateway.config;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

  @Bean
  UrlBasedCorsConfigurationSource corsConfigurationSource(
      CorsProperties properties) {
    List<String> allowedOrigins = properties.getAllowedOrigins()
        .stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(origin -> !origin.isBlank())
        .distinct()
        .toList();

    if (allowedOrigins.contains("*")) {
      throw new IllegalStateException(
          "CORS wildcard origin is not allowed "
              + "when credentials are enabled.");
    }

    CorsConfiguration configuration = new CorsConfiguration();

    configuration.setAllowedOrigins(allowedOrigins);

    configuration.setAllowedMethods(
        List.of(
            HttpMethod.GET.name(),
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name(),
            HttpMethod.OPTIONS.name()));

    configuration.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            "X-Request-ID",
            "Idempotency-Key"));

    configuration.setExposedHeaders(
        List.of(
            "X-Request-ID",
            HttpHeaders.RETRY_AFTER,
            HttpHeaders.LOCATION));

    configuration.setAllowCredentials(true);
    configuration.setMaxAge(properties.getMaxAgeSeconds());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

    source.registerCorsConfiguration(
        "/**",
        configuration);

    return source;
  }
}