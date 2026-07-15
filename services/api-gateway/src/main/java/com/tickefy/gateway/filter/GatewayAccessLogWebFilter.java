package com.tickefy.gateway.filter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
public final class GatewayAccessLogWebFilter
        implements WebFilter, Ordered {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("gateway.access");

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            WebFilterChain chain) {
        long startedAtNanos = System.nanoTime();

        AtomicBoolean logged = new AtomicBoolean(false);

        /*
         * Log when the response is about to commit:
         * - status has been resolved;
         * - route has been resolved if the request was proxied;
         * - Spring Security has completed JWT authentication if present.
         */
        exchange.getResponse().beforeCommit(() -> resolveUserId(exchange)
                .defaultIfEmpty("anonymous")
                .doOnNext(userId -> writeAccessLog(
                        exchange,
                        startedAtNanos,
                        userId,
                        logged))
                .then());

        return chain.filter(exchange);
    }

    private Mono<String> resolveUserId(
            ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(authentication -> authentication
                        .getToken()
                        .getSubject())
                .filter(StringUtils::hasText);
    }

    private void writeAccessLog(
            ServerWebExchange exchange,
            long startedAtNanos,
            String userId,
            AtomicBoolean logged) {
        if (!logged.compareAndSet(false, true)) {
            return;
        }

        long responseCommitDurationMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAtNanos);

        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();

        int status = statusCode != null
                ? statusCode.value()
                : 200;

        Route route = exchange.getAttribute(
                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        String routeId = route != null
                ? route.getId()
                : "unmatched";

        String requestId = RequestContextWebFilter.getRequestId(exchange);
        String method = exchange.getRequest()
                .getMethod()
                .name();
        String path = exchange.getRequest()
                .getPath()
                .value();
        String outcome = resolveOutcome(status);
        String clientIp = resolveClientIp(exchange);

        ACCESS_LOG.atInfo()
                .addKeyValue(
                        "event",
                        "gateway_access")
                .addKeyValue(
                        "requestId",
                        requestId)
                .addKeyValue(
                        "method",
                        method)
                .addKeyValue(
                        "path",
                        path)
                .addKeyValue(
                        "status",
                        status)
                .addKeyValue(
                        "outcome",
                        outcome)
                .addKeyValue(
                        "routeId",
                        routeId)
                .addKeyValue(
                        "userId",
                        userId)
                .addKeyValue(
                        "clientIp",
                        clientIp)
                .addKeyValue(
                        "responseCommitDurationMs",
                        responseCommitDurationMs)
                .log(String.format(
                        "event=gateway_access requestId=%s method=%s path=%s status=%d outcome=%s routeId=%s userId=%s clientIp=%s responseCommitDurationMs=%d",
                        requestId,
                        method,
                        path,
                        status,
                        outcome,
                        routeId,
                        userId,
                        clientIp,
                        responseCommitDurationMs));
    }

    private String resolveOutcome(int status) {
        if (status < 100 || status > 599) {
            return "UNKNOWN";
        }

        return (status / 100) + "xx";
    }

    private String resolveClientIp(
            ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null) {
            return "unknown";
        }

        InetAddress address = remoteAddress.getAddress();

        if (address != null) {
            return address.getHostAddress();
        }

        return remoteAddress.getHostString();
    }

    /*
     * RequestContextWebFilter uses HIGHEST_PRECEDENCE.
     * Access logging runs right after it but still before Security.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
