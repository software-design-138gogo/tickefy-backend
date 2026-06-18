package com.tickefy.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.rate-limit.enabled=false",
    "management.health.redis.enabled=false",
    "logging.level.gateway.access=OFF"
})
@AutoConfigureWebTestClient
class GatewayIntegrationTest {

  private static final String VALID_TOKEN = "valid-token";

  private static final String INVALID_TOKEN = "invalid-token";

  private static final DisposableServer DOWNSTREAM_STUB = startDownstreamStub();

  private static final int UNAVAILABLE_PORT = findUnusedPort();

  @Autowired
  private WebTestClient webTestClient;

  @MockitoBean
  private ReactiveJwtDecoder jwtDecoder;

  @DynamicPropertySource
  static void registerProperties(
      DynamicPropertyRegistry registry) {
    String stubBaseUrl = "http://127.0.0.1:"
        + DOWNSTREAM_STUB.port();

    registry.add(
        "app.services.auth-url",
        () -> stubBaseUrl);

    registry.add(
        "app.services.event-url",
        () -> stubBaseUrl);

    registry.add(
        "app.services.ticket-url",
        () -> stubBaseUrl);

    registry.add(
        "app.services.order-url",
        () -> stubBaseUrl);

    /*
     * Payment được trỏ tới port không có server
     * để test SERVICE_UNAVAILABLE.
     */
    registry.add(
        "app.services.payment-url",
        () -> "http://127.0.0.1:"
            + UNAVAILABLE_PORT);

    registry.add(
        "app.services.notification-url",
        () -> stubBaseUrl);

    registry.add(
        "app.services.checkin-url",
        () -> stubBaseUrl);

    registry.add(
        "app.services.inventory-url",
        () -> stubBaseUrl);

    registry.add(
        "app.services.ai-bio-url",
        () -> stubBaseUrl);

    registry.add(
        "app.services.csv-ingestion-url",
        () -> stubBaseUrl);

    registry.add(
        "spring.cloud.gateway.server.webflux."
            + "httpclient.connect-timeout",
        () -> "500");

    registry.add(
        "spring.cloud.gateway.server.webflux."
            + "httpclient.response-timeout",
        () -> "2s");
  }

  @BeforeEach
  void configureJwtDecoder() {
    reset(jwtDecoder);

    when(jwtDecoder.decode(VALID_TOKEN))
        .thenReturn(
            Mono.just(createValidJwt()));

    when(jwtDecoder.decode(INVALID_TOKEN))
        .thenReturn(
            Mono.error(
                new BadJwtException(
                    "Invalid test token")));
  }

  @AfterAll
  static void stopDownstreamStub() {
    DOWNSTREAM_STUB.disposeNow();
  }

  /*
   * =====================================================
   * ROUTING
   * =====================================================
   */

  @Test
  void shouldRewriteAuthRoute() {
    webTestClient.post()
        .uri("/api/auth/login")
        .header(
            "X-Request-ID",
            "route-auth-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "email": "test@example.com",
              "password": "password"
            }
            """)
        .exchange()

        .expectStatus()
        .isOk()

        .expectHeader()
        .valueEquals(
            "X-Stub-Path",
            "/auth/login")

        .expectHeader()
        .valueEquals(
            "X-Stub-Request-ID",
            "route-auth-001")

        .expectBody()
        .jsonPath("$.success")
        .isEqualTo(true);
  }

  @Test
  void shouldKeepEventRoutePathUnchanged() {
    webTestClient.get()
        .uri("/api/concerts")
        .exchange()

        .expectStatus()
        .isOk()

        .expectHeader()
        .valueEquals(
            "X-Stub-Path",
            "/api/concerts");
  }

  @Test
  void shouldRewriteOrderRoute() {
    webTestClient.get()
        .uri("/api/orders/order-001")
        .header(
            HttpHeaders.AUTHORIZATION,
            bearer(VALID_TOKEN))
        .exchange()

        .expectStatus()
        .isOk()

        .expectHeader()
        .valueEquals(
            "X-Stub-Path",
            "/orders/order-001");
  }

  @Test
  void shouldRewriteMyOrdersRoute() {
    webTestClient.get()
        .uri("/api/users/me/orders")
        .header(
            HttpHeaders.AUTHORIZATION,
            bearer(VALID_TOKEN))
        .exchange()

        .expectStatus()
        .isOk()

        .expectHeader()
        .valueEquals(
            "X-Stub-Path",
            "/users/me/orders");
  }

  /*
   * =====================================================
   * SECURITY
   * =====================================================
   */

  @Test
  void shouldAllowPublicEndpointWithoutToken() {
    webTestClient.get()
        .uri("/api/concerts")
        .exchange()

        .expectStatus()
        .isOk();
  }

  @Test
  void shouldRejectProtectedEndpointWithoutToken() {
    webTestClient.get()
        .uri("/api/orders/order-001")
        .header(
            "X-Request-ID",
            "security-401-001")
        .exchange()

        .expectStatus()
        .isUnauthorized()

        .expectHeader()
        .valueEquals(
            "X-Request-ID",
            "security-401-001")

        .expectBody()
        .jsonPath("$.success")
        .isEqualTo(false)

        .jsonPath("$.data")
        .isEqualTo(null)

        .jsonPath("$.error.httpStatus")
        .isEqualTo(401)

        .jsonPath("$.error.code")
        .isEqualTo("UNAUTHORIZED")

        .jsonPath("$.requestId")
        .isEqualTo("security-401-001");
  }

  @Test
  void shouldRejectInvalidToken() {
    webTestClient.get()
        .uri("/api/orders/order-001")
        .header(
            HttpHeaders.AUTHORIZATION,
            bearer(INVALID_TOKEN))
        .header(
            "X-Request-ID",
            "security-invalid-001")
        .exchange()

        .expectStatus()
        .isUnauthorized()

        .expectHeader()
        .valueEquals(
            HttpHeaders.WWW_AUTHENTICATE,
            "Bearer error=\"invalid_token\"")

        .expectBody()
        .jsonPath("$.error.code")
        .isEqualTo("INVALID_TOKEN")

        .jsonPath("$.requestId")
        .isEqualTo("security-invalid-001");
  }

  @Test
  void shouldAllowValidTokenAndForwardAuthorization() {
    webTestClient.get()
        .uri("/api/orders/order-001")
        .header(
            HttpHeaders.AUTHORIZATION,
            bearer(VALID_TOKEN))
        .exchange()

        .expectStatus()
        .isOk()

        /*
         * Không echo token thật.
         * Stub chỉ trả boolean xác nhận header tồn tại.
         */
        .expectHeader()
        .valueEquals(
            "X-Stub-Has-Authorization",
            "true");
  }

  /*
   * =====================================================
   * REQUEST ID AND HEADER HYGIENE
   * =====================================================
   */

  @Test
  void shouldGenerateAndForwardRequestId() {
    EntityExchangeResult<byte[]> result = webTestClient.get()
        .uri("/api/concerts")
        .exchange()

        .expectStatus()
        .isOk()

        .expectBody()
        .returnResult();

    String responseRequestId = result.getResponseHeaders()
        .getFirst("X-Request-ID");

    String downstreamRequestId = result.getResponseHeaders()
        .getFirst("X-Stub-Request-ID");

    assertThat(responseRequestId)
        .isNotBlank()
        .matches(
            "^[0-9a-fA-F]{8}-"
                + "[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{12}$");

    assertThat(downstreamRequestId)
        .isEqualTo(responseRequestId);
  }

  @Test
  void shouldRemoveSpoofedIdentityHeaders() {
    webTestClient.get()
        .uri("/api/orders/order-001")
        .header(
            HttpHeaders.AUTHORIZATION,
            bearer(VALID_TOKEN))
        .header(
            "X-User-ID",
            "fake-admin-user")
        .header(
            "X-User-Roles",
            "ADMIN")
        .exchange()

        .expectStatus()
        .isOk()

        .expectHeader()
        .doesNotExist("X-Stub-User-ID")

        .expectHeader()
        .doesNotExist("X-Stub-User-Roles");
  }

  /*
   * =====================================================
   * CORS
   * =====================================================
   */

  @Test
  void shouldAllowValidCorsPreflight() {
    webTestClient.method(HttpMethod.OPTIONS)
        .uri("/api/orders")
        .header(
            HttpHeaders.ORIGIN,
            "http://localhost:5173")
        .header(
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
            HttpMethod.POST.name())
        .header(
            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
            "authorization,content-type,"
                + "x-request-id,idempotency-key")
        .header(
            "X-Request-ID",
            "cors-preflight-001")
        .exchange()

        .expectStatus()
        .isOk()

        .expectHeader()
        .valueEquals(
            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
            "http://localhost:5173")

        .expectHeader()
        .valueEquals(
            HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
            "true")

        .expectHeader()
        .value(
            HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
            methods -> assertThat(methods)
                .contains("GET")
                .contains("POST")
                .contains("PUT")
                .contains("PATCH")
                .contains("DELETE")
                .contains("OPTIONS"))

        .expectHeader()
        .value(
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
            headers -> assertThat(headers.toLowerCase())
                .contains("authorization")
                .contains("content-type")
                .contains("x-request-id")
                .contains("idempotency-key"))

        .expectHeader()
        .valueEquals(
            HttpHeaders.ACCESS_CONTROL_MAX_AGE,
            "3600")

        .expectHeader()
        .valueEquals(
            "X-Request-ID",
            "cors-preflight-001");
  }

  @Test
  void shouldRejectInvalidCorsOrigin() {
    webTestClient.method(HttpMethod.OPTIONS)
        .uri("/api/orders")
        .header(
            HttpHeaders.ORIGIN,
            "https://evil.example.com")
        .header(
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
            HttpMethod.POST.name())
        .exchange()

        .expectStatus()
        .isForbidden()

        .expectHeader()
        .doesNotExist(
            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
  }

  @Test
  void shouldRejectCorsPreflightWithDisallowedHeader() {
    webTestClient.method(HttpMethod.OPTIONS)
        .uri("/api/orders")
        .header(
            HttpHeaders.ORIGIN,
            "http://localhost:5173")
        .header(
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
            HttpMethod.POST.name())
        .header(
            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
            "authorization,x-not-allowed")
        .exchange()

        .expectStatus()
        .isForbidden()

        .expectHeader()
        .doesNotExist(
            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
  }

  /*
   * =====================================================
   * ERROR HANDLING
   * =====================================================
   */

  @Test
  void shouldReturnCommonNotFoundEnvelope() {
    webTestClient.get()
        .uri("/api/non-existing-resource")
        .header(
            HttpHeaders.AUTHORIZATION,
            bearer(VALID_TOKEN))
        .header(
            "X-Request-ID",
            "not-found-001")
        .exchange()

        .expectStatus()
        .isNotFound()

        .expectBody()
        .jsonPath("$.success")
        .isEqualTo(false)

        .jsonPath("$.data")
        .isEqualTo(null)

        .jsonPath("$.error.httpStatus")
        .isEqualTo(404)

        .jsonPath("$.error.code")
        .isEqualTo("RESOURCE_NOT_FOUND")

        .jsonPath("$.requestId")
        .isEqualTo("not-found-001");
  }

  @Test
  void shouldReturnServiceUnavailableWhenDownstreamIsDown() {
    webTestClient.get()
        .uri("/api/payments/test-payment")
        .header(
            HttpHeaders.AUTHORIZATION,
            bearer(VALID_TOKEN))
        .header(
            "X-Request-ID",
            "service-unavailable-001")
        .exchange()

        .expectStatus()
        .isEqualTo(503)

        .expectBody()
        .jsonPath("$.error.httpStatus")
        .isEqualTo(503)

        .jsonPath("$.error.code")
        .isEqualTo("SERVICE_UNAVAILABLE")

        .jsonPath("$.requestId")
        .isEqualTo("service-unavailable-001");
  }

  @Test
  void shouldPreserveDownstreamBusinessError() {
    webTestClient.get()
        .uri("/api/inventory/test-conflict")
        .exchange()

        .expectStatus()
        .isEqualTo(409)

        .expectBody()
        .jsonPath("$.error.code")
        .isEqualTo("TICKET_SOLD_OUT");
  }

  /*
   * =====================================================
   * TEST HELPERS
   * =====================================================
   */

  private static Jwt createValidJwt() {
    Instant now = Instant.now();

    return Jwt.withTokenValue(VALID_TOKEN)
        .header("alg", "RS256")
        .subject("test-user-001")
        .issuer("tickefy-auth-service")
        .audience(
            List.of("tickefy-api"))
        .issuedAt(
            now.minusSeconds(10))
        .expiresAt(
            now.plusSeconds(300))
        .claim(
            "roles",
            List.of("AUDIENCE"))
        .build();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static DisposableServer startDownstreamStub() {
    return HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle(
            GatewayIntegrationTest::handleDownstreamRequest)
        .bindNow();
  }

  private static Mono<Void> handleDownstreamRequest(
      HttpServerRequest request,
      HttpServerResponse response) {
    String path = request.uri();

    /*
     * Dùng để chứng minh Gateway không rewrite
     * business error từ downstream.
     */
    if ("/api/inventory/test-conflict"
        .equals(path)) {
      response.status(409);

      response.header(
          HttpHeaders.CONTENT_TYPE,
          MediaType.APPLICATION_JSON_VALUE);

      return response.sendString(
          Mono.just("""
              {
                "success": false,
                "data": null,
                "error": {
                  "httpStatus": 409,
                  "code": "TICKET_SOLD_OUT",
                  "message": "Ticket sold out",
                  "details": {}
                },
                "requestId": "downstream-error",
                "timestamp": "2026-06-17T00:00:00Z"
              }
              """)).then();
    }

    response.status(200);

    response.header(
        HttpHeaders.CONTENT_TYPE,
        MediaType.APPLICATION_JSON_VALUE);

    response.header(
        "X-Stub-Path",
        path);

    copyRequestHeader(
        request,
        response,
        "X-Request-ID",
        "X-Stub-Request-ID");

    copyRequestHeader(
        request,
        response,
        "X-User-ID",
        "X-Stub-User-ID");

    copyRequestHeader(
        request,
        response,
        "X-User-Roles",
        "X-Stub-User-Roles");

    response.header(
        "X-Stub-Has-Authorization",
        Boolean.toString(
            request.requestHeaders()
                .contains(
                    HttpHeaders.AUTHORIZATION)));

    return response.sendString(
        Mono.just("""
            {
              "success": true,
              "data": {
                "source": "downstream-stub"
              },
              "error": null
            }
            """)).then();
  }

  private static void copyRequestHeader(
      HttpServerRequest request,
      HttpServerResponse response,
      String requestHeader,
      String responseHeader) {
    String value = request.requestHeaders()
        .get(requestHeader);

    if (value != null) {
      response.header(
          responseHeader,
          value);
    }
  }

  private static int findUnusedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {

      return socket.getLocalPort();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to allocate test port",
          exception);
    }
  }
}
