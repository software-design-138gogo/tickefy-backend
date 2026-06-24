package com.tickefy.csvingestion.modules.csvimport.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter bean for inventory-service. Circuit breaker lives HERE (cross-bean proxy, CLAUDE §8) so
 * callers invoke {@code getTicketTypes} through the Spring proxy. Do NOT self-invoke from within.
 * GET /api/inventory/concerts/{id}/ticket-types is PUBLIC (no bearer required).
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public InventoryClient(
            @Value("${app.inventory.base-url}") String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Real connect + read timeouts (2s) via JDK HttpClient request factory (mirror EventClient/checkin).
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2000)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(2000));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * GET /api/inventory/concerts/{concertId}/ticket-types (public).
     * 5xx / timeout / connect -> InventoryUnavailableException (recorded by CB).
     * Empty concert -> 200 with empty list (not an error).
     */
    @CircuitBreaker(name = "inventory-service", fallbackMethod = "getTicketTypesFallback")
    public List<TicketTypeSummary> getTicketTypes(UUID concertId) {
        try {
            byte[] body = restClient.get()
                    .uri("/api/inventory/concerts/{id}/ticket-types", concertId)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        byte[] payload = response.getBody().readAllBytes();
                        if (status.is2xxSuccessful()) {
                            return payload;
                        }
                        throw new InventoryUnavailableException("Inventory returned " + status.value());
                    });
            return parse(body);
        } catch (InventoryUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new InventoryUnavailableException("Inventory service error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new InventoryUnavailableException("Unexpected inventory client error", e);
        }
    }

    List<TicketTypeSummary> getTicketTypesFallback(UUID concertId, Throwable t) {
        if (t instanceof ApiException apiException) {
            throw apiException;
        }
        log.warn("Inventory CB fallback concertId={} cause={}", concertId, t.toString());
        throw new ApiException(
                ErrorCode.SERVICE_UNAVAILABLE, "Inventory service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private List<TicketTypeSummary> parse(byte[] body) {
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            List<TicketTypeSummary> out = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode n : data) {
                    out.add(new TicketTypeSummary(
                            UUID.fromString(n.path("id").asText()), n.path("name").asText(null)));
                }
            }
            return out;
        } catch (Exception e) {
            throw new InventoryUnavailableException(
                    "Failed to parse inventory response: " + e.getMessage(), e);
        }
    }
}
