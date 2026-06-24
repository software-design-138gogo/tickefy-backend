package com.tickefy.csvingestion.modules.csvimport.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import com.tickefy.csvingestion.modules.csvimport.resolver.TicketTypeMap;
import com.tickefy.csvingestion.modules.csvimport.resolver.TicketTypeResolver;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.Optional;
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
 * T-csv-4a: InventoryClient + TicketTypeResolver integration tests (WireMock).
 *
 * <p>AC coverage:
 * <ul>
 *   <li>AC1-happy: 200 envelope data[] → TicketTypeSummary list; resolver resolves "VIP"→uuid1.</li>
 *   <li>AC2-case-insensitive: resolve("vip")→uuid1; resolve(" Vip ")→uuid1 (trim+lower).</li>
 *   <li>AC3-unknown: resolve("XYZ")→Optional.empty.</li>
 *   <li>AC4-empty-list: 200 data:[] → empty list; resolver.resolve("VIP")→empty.</li>
 *   <li>AC5-cb-open: repeated 5xx → CB OPEN; fallback throws ApiException SERVICE_UNAVAILABLE 503.</li>
 * </ul>
 *
 * <p>Uses @SpringBootTest so InventoryClient bean goes through Spring AOP proxy (CB annotation active).
 * DB = H2 in-memory via application-test.yml (Flyway disabled). Mirror of EventClientIntegrationTest.
 * CB window overridden to sliding-window-size=2, minimum-number-of-calls=2, failure-rate=100
 * so CB opens after exactly 2 consecutive failures.
 *
 * <p>CB state is reset to CLOSED @BeforeEach so each test starts with a clean CB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryClientIntegrationTest {

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
        wireMock.resetAll();
        // reset() transitions CB to CLOSED and clears sliding-window metrics (mirror EventClientIntegrationTest)
        circuitBreakerRegistry.circuitBreaker("inventory-service").reset();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // WireMock URL (supplier: evaluated after WireMock started)
        registry.add("app.inventory.base-url", () -> "http://localhost:" + wireMock.port());

        // Shrink CB window so OPEN triggers after 2 consecutive failures (not 5)
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.sliding-window-size",
                () -> "2");
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.minimum-number-of-calls",
                () -> "2");
        // 100% failure rate threshold: all 2 calls must fail to open
        registry.add(
                "resilience4j.circuitbreaker.instances.inventory-service.failure-rate-threshold",
                () -> "100");

        // Provide dummy object-storage props so MinioConfig @Bean can build MinioClient
        registry.add("app.object-storage.endpoint", () -> "http://localhost:9999");
        registry.add("app.object-storage.access-key", () -> "minioadmin");
        registry.add("app.object-storage.secret-key", () -> "minioadmin");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");
    }

    @Autowired
    InventoryClient inventoryClient;

    @Autowired
    TicketTypeResolver ticketTypeResolver;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    // UUIDs used across case-insensitive tests
    static final UUID UUID_VIP = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID UUID_GA = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID CONCERT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static String twoTypeBody() {
        return """
               {
                 "success": true,
                 "data": [
                   {"id": "%s", "concertId": "%s", "name": "VIP", "price": 500000},
                   {"id": "%s", "concertId": "%s", "name": "GA",  "price": 200000}
                 ],
                 "error": null,
                 "requestId": "req-inv-001"
               }
               """.formatted(UUID_VIP, CONCERT_ID, UUID_GA, CONCERT_ID);
    }

    // -----------------------------------------------------------------------
    // AC1: 200 envelope → list of 2 TicketTypeSummary; resolver resolves "VIP"→UUID_VIP
    // -----------------------------------------------------------------------

    @Test
    void ac1_happy_200envelope_returnsTwoSummaries_resolverFindsVip() {
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + CONCERT_ID + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(twoTypeBody())));

        List<TicketTypeSummary> result = inventoryClient.getTicketTypes(CONCERT_ID);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(TicketTypeSummary::id))
                .containsExactlyInAnyOrder(UUID_VIP, UUID_GA);
        assertThat(result.stream().map(TicketTypeSummary::name))
                .containsExactlyInAnyOrder("VIP", "GA");

        // Verify via resolver as well (AC1 includes resolver path)
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + CONCERT_ID + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(twoTypeBody())));

        TicketTypeMap map = ticketTypeResolver.loadForConcert(CONCERT_ID);
        assertThat(map.resolve("VIP")).isPresent().hasValue(UUID_VIP);
        assertThat(map.resolve("GA")).isPresent().hasValue(UUID_GA);
    }

    // -----------------------------------------------------------------------
    // AC2: case-insensitive resolve ("vip", " Vip " → UUID_VIP)
    // -----------------------------------------------------------------------

    @Test
    void ac2_caseInsensitive_resolveVariants_allReturnUuidVip() {
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + CONCERT_ID + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(twoTypeBody())));

        TicketTypeMap map = ticketTypeResolver.loadForConcert(CONCERT_ID);

        assertThat(map.resolve("vip"))
                .as("resolve(\"vip\") should return UUID_VIP (lowercase match)")
                .isPresent()
                .hasValue(UUID_VIP);

        assertThat(map.resolve(" Vip "))
                .as("resolve(\" Vip \") should return UUID_VIP (trim + lowercase match)")
                .isPresent()
                .hasValue(UUID_VIP);

        assertThat(map.resolve("VIP"))
                .as("resolve(\"VIP\") should return UUID_VIP (exact normalized match)")
                .isPresent()
                .hasValue(UUID_VIP);
    }

    // -----------------------------------------------------------------------
    // AC3: unknown name → Optional.empty
    // -----------------------------------------------------------------------

    @Test
    void ac3_unknownName_resolveReturnsEmpty() {
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + CONCERT_ID + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(twoTypeBody())));

        TicketTypeMap map = ticketTypeResolver.loadForConcert(CONCERT_ID);

        Optional<UUID> result = map.resolve("XYZ");
        assertThat(result)
                .as("resolve(\"XYZ\") must return Optional.empty for unknown ticket type")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC4: empty data[] → empty list; resolver.resolve("VIP") → empty
    // -----------------------------------------------------------------------

    @Test
    void ac4_emptyList_200dataEmpty_returnsEmpty_resolverEmpty() {
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + CONCERT_ID + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                          {
                                            "success": true,
                                            "data": [],
                                            "error": null,
                                            "requestId": "req-inv-empty"
                                          }
                                          """)));

        List<TicketTypeSummary> list = inventoryClient.getTicketTypes(CONCERT_ID);
        assertThat(list)
                .as("Empty data[] must produce empty list")
                .isEmpty();

        // resolver also returns empty map → resolve("VIP") = empty
        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + CONCERT_ID + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                          {
                                            "success": true,
                                            "data": [],
                                            "error": null,
                                            "requestId": "req-inv-empty-2"
                                          }
                                          """)));

        TicketTypeMap map = ticketTypeResolver.loadForConcert(CONCERT_ID);
        assertThat(map.resolve("VIP"))
                .as("resolve(\"VIP\") must be empty when inventory returns empty list")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC5: 5xx * minimum-number-of-calls → CB OPEN; fallback throws ApiException 503
    // -----------------------------------------------------------------------

    @Test
    void ac5_cbOpen_repeated5xx_cbOpens_fallbackThrows503() {
        UUID cbConcertId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        wireMock.stubFor(
                get(urlPathEqualTo("/api/inventory/concerts/" + cbConcertId + "/ticket-types"))
                        .willReturn(aResponse()
                                .withStatus(500)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"success\":false,\"error\":{\"code\":\"INTERNAL\"}}")));

        // minimum-number-of-calls = 2 (overridden via @DynamicPropertySource)
        int minCalls = 2;
        for (int i = 0; i < minCalls; i++) {
            int attempt = i;
            assertThatThrownBy(() -> inventoryClient.getTicketTypes(cbConcertId))
                    .as("Call %d should throw InventoryUnavailableException (CB still CLOSED or recording)",
                            attempt)
                    .isInstanceOf(InventoryUnavailableException.class);
        }

        // CB must now be OPEN (failure-rate = 100% >= threshold 100%)
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("inventory-service");
        assertThat(cb.getState())
                .as("CB must be OPEN after %d consecutive 500 failures", minCalls)
                .isEqualTo(CircuitBreaker.State.OPEN);

        // Next call must go to fallback → ApiException SERVICE_UNAVAILABLE 503
        assertThatThrownBy(() -> inventoryClient.getTicketTypes(cbConcertId))
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
