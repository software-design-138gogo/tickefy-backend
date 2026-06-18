package com.tickefy.gateway.filter;

import java.util.Map;
import java.util.regex.Pattern;

import com.tickefy.gateway.config.UploadLimitProperties;
import com.tickefy.gateway.error.GatewayErrorWriter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public final class UploadSizeGlobalFilter
    implements GlobalFilter, Ordered {

  private static final Pattern AI_BIO_UPLOAD_PATH = Pattern.compile(
      "^/api/ai-bio/concerts/[^/]+/jobs/?$");

  private static final String CSV_UPLOAD_PATH = "/api/admin/csv-import";

  private final UploadLimitProperties properties;
  private final GatewayErrorWriter errorWriter;

  public UploadSizeGlobalFilter(
      UploadLimitProperties properties,
      GatewayErrorWriter errorWriter) {
    this.properties = properties;
    this.errorWriter = errorWriter;
  }

  @Override
  public Mono<Void> filter(
      ServerWebExchange exchange,
      GatewayFilterChain chain) {
    UploadRule rule = resolveUploadRule(exchange.getRequest());

    if (rule == null) {
      return chain.filter(exchange);
    }

    long contentLength = exchange.getRequest()
        .getHeaders()
        .getContentLength();

    /*
     * No Content-Length, for example chunked requests:
     * Gateway does not parse or buffer the body.
     * Downstream services must still enforce actual limits.
     */
    if (contentLength < 0) {
      return chain.filter(exchange);
    }

    if (contentLength <= rule.maxRequestSizeBytes()) {
      return chain.filter(exchange);
    }

    return errorWriter.write(
        exchange,
        HttpStatus.PAYLOAD_TOO_LARGE,
        rule.errorCode(),
        rule.message(),
        Map.of(
            "maxRequestSizeBytes",
            rule.maxRequestSizeBytes(),
            "actualRequestSizeBytes",
            contentLength));
  }

  private UploadRule resolveUploadRule(
      ServerHttpRequest request) {
    if (!HttpMethod.POST.equals(request.getMethod())) {
      return null;
    }

    String path = request.getURI().getPath();

    if (AI_BIO_UPLOAD_PATH.matcher(path).matches()) {
      return new UploadRule(
          properties
              .getAiBioMaxRequestSize()
              .toBytes(),
          "PDF_TOO_LARGE",
          "Total PDF size exceeds the limit.");
    }

    if (CSV_UPLOAD_PATH.equals(path)
        || (CSV_UPLOAD_PATH + "/").equals(path)) {
      return new UploadRule(
          properties
              .getCsvMaxRequestSize()
              .toBytes(),
          "FILE_TOO_LARGE",
          "File size exceeds the limit.");
    }

    return null;
  }

  /*
   * RateLimitGlobalFilter uses -100.
   * Upload size pre-check runs after rate limiting but before routing.
   */
  @Override
  public int getOrder() {
    return -90;
  }

  private record UploadRule(
      long maxRequestSizeBytes,
      String errorCode,
      String message) {
  }
}
