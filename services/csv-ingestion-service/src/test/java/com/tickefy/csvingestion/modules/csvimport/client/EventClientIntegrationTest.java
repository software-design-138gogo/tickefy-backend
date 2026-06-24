package com.tickefy.csvingestion.modules.csvimport.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T-csv-3a-2: EventClient integration tests (WireMock).
 *
 * <p>AC coverage:
 * <ul>
 *   <li>AC-happy: 200 envelope → ConcertSummary parsed; Bearer forwarded.</li>
 *   <li>AC-404: 404 → ConcertNotFoundException; CB stays CLOSED (business, not infra).</li>
 *   <li>AC-cb-open: 5xx × min-calls → CB OPEN; fallback throws SERVICE_UNAVAILABLE 503.</li>
 * </ul>
 *
 * <p>Uses @SpringBootTest so EventClient bean goes through Spring AOP proxy (CB annotation active).
 * DB = H2 in-memory via application-test.yml (Flyway disabled).
 * CB window overridden to sliding-window-size=3, minimum-number-of-calls=3, failure-rate=100
 * so CB opens after exactly 3 consecutive 500 failures.
 *
 * <p>CB state is reset to CLOSED in @BeforeEach so each test starts with a clean CB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventClientIntegrationTest {

    // --- WireMock lifecycle (static, shared for all tests in class) ---

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetState() {
        // Reset WireMock stubs
        wireMock.resetAll();
        // cb.reset() transitions to CLOSED AND clears sliding window metrics
        // Works from any state (CLOSED, OPEN, HALF_OPEN, etc.)
        circuitBreakerRegistry.circuitBreaker("event-service").reset();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // WireMock URL registered lazily — supplier evaluated after WireMock started
        registry.add("app.event.base-url", () -> "http://localhost:" + wireMock.port());

        // Shrink CB window so OPEN triggers after 3 consecutive failures (not 5)
        registry.add(
                "resilience4j.circuitbreaker.instances.event-service.sliding-window-size", () -> "3");
        registry.add(
                "resilience4j.circuitbreaker.instances.event-service.minimum-number-of-calls",
                () -> "3");
        // 100% failure rate threshold: all 3 calls must fail to open
        registry.add(
                "resilience4j.circuitbreaker.instances.event-service.failure-rate-threshold",
                () -> "100");

        // Provide dummy object-storage props so MinioConfig @Bean can build MinioClient
        registry.add("app.object-storage.endpoint", () -> "http://localhost:9999");
        registry.add("app.object-storage.access-key", () -> "minioadmin");
        registry.add("app.object-storage.secret-key", () -> "minioadmin");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");
    }

    @Autowired
    EventClient eventClient;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    // -----------------------------------------------------------------------
    // AC-happy: 200 → ConcertSummary + Bearer forwarded
    // -----------------------------------------------------------------------

    @Test
    void acHappy_200envelope_returnsConcertSummary_bearerForwarded() {
        UUID concertId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID organizerId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        wireMock.stubFor(
                get(urlPathEqualTo("/internal/concerts/" + concertId))
                        .withHeader("Authorization", equalTo("Bearer test-token-abc"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        """
                                        {
                                          "success": true,
                                          "data": {
                                            "id": "%s",
                                            "organizerId": "%s",
                                            "status": "PUBLISHED"
                                          },
                                          "error": null,
                                          "requestId": "req-001"
                                        }
                                        """
                                                .formatted(concertId, organizerId))));

        ConcertSummary result = eventClient.getConcert(concertId, "test-token-abc");

        assertThat(result.id()).isEqualTo(concertId);
        assertThat(result.organizerId()).isEqualTo(organizerId);
        assertThat(result.status()).isEqualTo("PUBLISHED");

        // Verify WireMock received exactly 1 call with the correct Bearer header
        wireMock.verify(
                1,
                getRequestedFor(urlPathEqualTo("/internal/concerts/" + concertId))
                        .withHeader("Authorization", equalTo("Bearer test-token-abc")));
    }

    // -----------------------------------------------------------------------
    // AC-404: 404 → ConcertNotFoundException; CB STAYS CLOSED
    // -----------------------------------------------------------------------

    @Test
    void ac404_notFound_throwsConcertNotFoundException_cbStaysClosed() {
        UUID concertId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        wireMock.stubFor(
                get(urlPathEqualTo("/internal/concerts/" + concertId))
                        .willReturn(aResponse()
                                .withStatus(404)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        """
                                        {
                                          "success": false,
                                          "data": null,
                                          "error": { "code": "CONCERT_NOT_FOUND", "message": "not found" },
                                          "requestId": "req-404"
                                        }
                                        """)));

        assertThatThrownBy(() -> eventClient.getConcert(concertId, "any-token"))
                .isInstanceOf(ConcertNotFoundException.class)
                .satisfies(ex -> {
                    ConcertNotFoundException cnfe = (ConcertNotFoundException) ex;
                    assertThat(cnfe.getErrorCode()).isEqualTo(ErrorCode.CONCERT_NOT_FOUND);
                });

        // CB must remain CLOSED — 404 is NOT a recorded exception
        CircuitBreaker.State state =
                circuitBreakerRegistry.circuitBreaker("event-service").getState();
        assertThat(state)
                .as("CB must remain CLOSED after 404 (business exception, not infra)")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // -----------------------------------------------------------------------
    // AC-cb-open: repeated 5xx → CB OPEN → fallback 503
    // -----------------------------------------------------------------------

    @Test
    void acCbOpen_repeated5xx_cbOpens_fallbackThrows503() {
        UUID concertId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        // Stub 500 for all calls
        wireMock.stubFor(
                get(urlPathEqualTo("/internal/concerts/" + concertId))
                        .willReturn(aResponse()
                                .withStatus(500)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"success\":false,\"error\":{\"code\":\"INTERNAL\"}}")));

        // Fire minimum-number-of-calls (=3 via @DynamicPropertySource) — each must throw
        // EventUnavailableException (infra failure, CB records it)
        int minCalls = 3;
        for (int i = 0; i < minCalls; i++) {
            int attempt = i;
            assertThatThrownBy(() -> eventClient.getConcert(concertId, "token"))
                    .as("Call %d should throw EventUnavailableException (CB still CLOSED or HALF-OPEN)",
                            attempt)
                    .isInstanceOf(EventUnavailableException.class);
        }

        // CB must now be OPEN (failure-rate = 100% >= threshold 100%)
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("event-service");
        assertThat(cb.getState())
                .as("CB must be OPEN after %d consecutive 500 failures", minCalls)
                .isEqualTo(CircuitBreaker.State.OPEN);

        // Next call must go to fallback → ApiException SERVICE_UNAVAILABLE 503
        assertThatThrownBy(() -> eventClient.getConcert(concertId, "token"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode())
                            .as("Fallback must use SERVICE_UNAVAILABLE error code")
                            .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getStatus().value())
                            .as("Fallback HTTP status must be 503")
                            .isEqualTo(503);
                });
    }
}
