package com.tickefy.gateway.filter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

import com.tickefy.gateway.config.RateLimitProperties;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public final class RateLimitKeyResolver {

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private static final Pattern SAFE_IP = Pattern.compile("^[0-9A-Fa-f:.]{1,64}$");

  private final RateLimitProperties properties;

  public RateLimitKeyResolver(
      RateLimitProperties properties) {
    this.properties = properties;
  }

  public Mono<String> resolve(
      ServerWebExchange exchange) {
    return exchange.getPrincipal()
        .ofType(Authentication.class)
        .filter(Authentication::isAuthenticated)
        .map(this::resolveAuthenticatedKey)
        .filter(StringUtils::hasText)
        .switchIfEmpty(
            Mono.fromSupplier(() -> "ip:" + resolveClientIp(exchange)));
  }

  private String resolveAuthenticatedKey(
      Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {

      String subject = jwtAuthentication
          .getToken()
          .getSubject();

      if (StringUtils.hasText(subject)) {
        return "user:" + sanitize(subject);
      }
    }

    return null;
  }

  private String resolveClientIp(
      ServerWebExchange exchange) {
    if (properties.isTrustForwardedHeaders()) {
      String forwardedIp = resolveForwardedIp(exchange);

      if (forwardedIp != null) {
        return forwardedIp;
      }
    }

    InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();

    if (remoteAddress == null) {
      return "unknown";
    }

    InetAddress address = remoteAddress.getAddress();

    if (address != null) {
      return address.getHostAddress();
    }

    return sanitize(remoteAddress.getHostString());
  }

  private String resolveForwardedIp(
      ServerWebExchange exchange) {
    String forwardedFor = exchange.getRequest()
        .getHeaders()
        .getFirst(
            X_FORWARDED_FOR);

    if (!StringUtils.hasText(forwardedFor)) {
      return null;
    }

    String firstIp = forwardedFor
        .split(",", 2)[0]
        .trim();

    if (!SAFE_IP.matcher(firstIp).matches()) {
      return null;
    }

    return firstIp;
  }

  private String sanitize(String value) {
    String sanitized = value.replaceAll(
        "[^A-Za-z0-9._:-]",
        "_");

    if (sanitized.length() <= 100) {
      return sanitized;
    }

    return sanitized.substring(0, 100);
  }
}
