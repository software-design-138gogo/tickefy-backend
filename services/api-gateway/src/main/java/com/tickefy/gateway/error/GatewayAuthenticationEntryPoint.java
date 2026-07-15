package com.tickefy.gateway.error;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public final class GatewayAuthenticationEntryPoint
    implements ServerAuthenticationEntryPoint {

  private final GatewayErrorWriter errorWriter;

  public GatewayAuthenticationEntryPoint(
      GatewayErrorWriter errorWriter) {
    this.errorWriter = errorWriter;
  }

  @Override
  public Mono<Void> commence(
      ServerWebExchange exchange,
      AuthenticationException exception) {
    boolean invalidToken = exception instanceof OAuth2AuthenticationException oauth2Exception
        && OAuth2ErrorCodes.INVALID_TOKEN.equals(
            oauth2Exception
                .getError()
                .getErrorCode());

    if (invalidToken) {
      exchange.getResponse()
          .getHeaders()
          .set(
              HttpHeaders.WWW_AUTHENTICATE,
              "Bearer error=\"invalid_token\"");

      return errorWriter.write(
          exchange,
          HttpStatus.UNAUTHORIZED,
          "INVALID_TOKEN",
          "Invalid token",
          Map.of());
    }

    exchange.getResponse()
        .getHeaders()
        .set(
            HttpHeaders.WWW_AUTHENTICATE,
            "Bearer");

    return errorWriter.write(
        exchange,
        HttpStatus.UNAUTHORIZED,
        "UNAUTHORIZED",
        "Unauthorized",
        Map.of());
  }
}