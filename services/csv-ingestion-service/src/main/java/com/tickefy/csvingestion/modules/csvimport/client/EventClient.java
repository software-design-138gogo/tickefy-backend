package com.tickefy.csvingestion.modules.csvimport.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.ConnectException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter bean for event-service. Circuit breaker lives HERE (cross-bean proxy, CLAUDE §8) so
 * callers invoke {@code getConcert} through the Spring proxy. Do NOT self-invoke from within.
 */
@Component
public class EventClient {

    private static final Logger log = LoggerFactory.getLogger(EventClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public EventClient(@Value("${app.event.base-url}") String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInitializer(req -> req.getHeaders().set("Connect-Timeout", "2000"))
                .build();
    }

    /**
     * GET /internal/concerts/{concertId} — forward caller's Bearer token.
     * 404 -> ConcertNotFoundException (business, CB stays closed).
     * 5xx / timeout / connect -> EventUnavailableException (recorded by CB).
     */
    @CircuitBreaker(name = "event-service", fallbackMethod = "getConcertFallback")
    public ConcertSummary getConcert(UUID concertId, String bearerToken) {
        try {
            byte[] body = restClient.get()
                    .uri("/internal/concerts/{id}", concertId)
                    .header("Authorization", "Bearer " + bearerToken)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        byte[] payload = response.getBody().readAllBytes();
                        if (status.is2xxSuccessful()) {
                            return payload;
                        }
                        handleError(status);
                        return null; // unreachable
                    });
            return parse(body);
        } catch (ConcertNotFoundException | EventUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException) {
                throw new EventUnavailableException("Event service unreachable", e);
            }
            throw new EventUnavailableException("Event service error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new EventUnavailableException("Unexpected event client error", e);
        }
    }

    ConcertSummary getConcertFallback(UUID concertId, String bearerToken, Throwable t) {
        // Resilience4j routes ALL exceptions from getConcert through this fallback, not only the
        // circuit-OPEN case. Propagate domain/infra ApiExceptions (ConcertNotFoundException=404,
        // EventUnavailableException=503) with their original status; only synthesize 503 for the
        // circuit-OPEN signal (CallNotPermittedException) or any unexpected throwable.
        if (t instanceof ApiException apiException) {
            throw apiException;
        }
        log.warn("Event CB fallback concertId={} cause={}", concertId, t.toString());
        throw new ApiException(
                ErrorCode.SERVICE_UNAVAILABLE, "Event service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void handleError(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            throw new ConcertNotFoundException("Concert not found");
        }
        throw new EventUnavailableException("Event service returned " + status.value());
    }

    private ConcertSummary parse(byte[] body) {
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            UUID id = UUID.fromString(data.path("id").asText());
            JsonNode organizerNode = data.path("organizerId");
            UUID organizerId = organizerNode.isMissingNode() || organizerNode.isNull()
                    ? null
                    : UUID.fromString(organizerNode.asText());
            String status = data.path("status").asText(null);
            return new ConcertSummary(id, organizerId, status);
        } catch (Exception e) {
            throw new EventUnavailableException("Failed to parse event response: " + e.getMessage(), e);
        }
    }
}
