package com.tickefy.gateway.error;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public final class GatewayAccessDeniedHandler
    implements ServerAccessDeniedHandler {

  private final GatewayErrorWriter errorWriter;

  public GatewayAccessDeniedHandler(
      GatewayErrorWriter errorWriter) {
    this.errorWriter = errorWriter;
  }

  @Override
  public Mono<Void> handle(
      ServerWebExchange exchange,
      AccessDeniedException exception) {
    return errorWriter.write(
        exchange,
        HttpStatus.FORBIDDEN,
        "FORBIDDEN",
        "Forbidden.",
        Map.of());
  }
}