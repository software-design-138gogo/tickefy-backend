package com.tickefy.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.gateway.config.UploadLimitProperties;
import com.tickefy.gateway.error.GatewayErrorWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;

class UploadSizeGlobalFilterTest {

  private UploadSizeGlobalFilter filter;

  @BeforeEach
  void setUp() {
    UploadLimitProperties properties = new UploadLimitProperties();

    properties.setAiBioMaxRequestSize(
        DataSize.ofMegabytes(30));

    properties.setCsvMaxRequestSize(
        DataSize.ofMegabytes(12));

    GatewayErrorWriter errorWriter = new GatewayErrorWriter(
        new ObjectMapper()
            .findAndRegisterModules());

    filter = new UploadSizeGlobalFilter(
        properties,
        errorWriter);
  }

  @Test
  void shouldRejectOversizedAiBioUpload() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .method(
                HttpMethod.POST,
                "/api/ai-bio/concerts/concert-1/jobs")
            .header(
                HttpHeaders.CONTENT_LENGTH,
                String.valueOf(
                    DataSize
                        .ofMegabytes(31)
                        .toBytes()))
            .build());

    AtomicBoolean forwarded = new AtomicBoolean(false);

    filter.filter(
        exchange,
        currentExchange -> {
          forwarded.set(true);
          return reactor.core.publisher.Mono.empty();
        }).block();

    assertThat(forwarded).isFalse();

    assertThat(
        exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

    assertThat(
        exchange.getResponse()
            .getBodyAsString()
            .block())
        .contains("\"code\":\"PDF_TOO_LARGE\"");
  }

  @Test
  void shouldRejectOversizedCsvUpload() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .post("/api/admin/csv-import")
            .header(
                HttpHeaders.CONTENT_LENGTH,
                String.valueOf(
                    DataSize
                        .ofMegabytes(13)
                        .toBytes()))
            .build());

    filter.filter(
        exchange,
        currentExchange -> reactor.core.publisher.Mono.empty()).block();

    assertThat(
        exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

    assertThat(
        exchange.getResponse()
            .getBodyAsString()
            .block())
        .contains("\"code\":\"FILE_TOO_LARGE\"");
  }

  @Test
  void shouldForwardUploadWithinGatewayLimit() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest
            .post("/api/admin/csv-import")
            .header(
                HttpHeaders.CONTENT_LENGTH,
                String.valueOf(
                    DataSize
                        .ofMegabytes(10)
                        .toBytes()))
            .build());

    AtomicBoolean forwarded = new AtomicBoolean(false);

    filter.filter(
        exchange,
        currentExchange -> {
          forwarded.set(true);
          return reactor.core.publisher.Mono.empty();
        }).block();

    assertThat(forwarded).isTrue();
  }
}