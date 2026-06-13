package com.tickefy.checkin.infrastructure.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.common.exception.ApiException;
import com.tickefy.checkin.common.exception.ErrorCode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed HTTP client to e-ticket-service.
 * Infrastructure failures are surfaced as service-unavailable errors instead of invalid QR results.
 */
@Component
public class ETicketClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public ETicketClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${eticket.service.url:http://localhost:8087}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public Optional<TicketInfo> getTicketByToken(String token) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/internal/tickets/by-token/{token}")
                    .build(token);
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    authorizedEntity(),
                    String.class);
            return Optional.of(readData(response.getBody(), TicketInfo.class));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpClientErrorException ex) {
            if ("INVALID_QR_TOKEN".equals(errorCode(ex.getResponseBodyAsString()))) {
                return Optional.empty();
            }
            throw downstreamUnavailable("e-ticket rejected token lookup", ex);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            throw downstreamUnavailable("e-ticket unavailable during token lookup", ex);
        }
    }

    public String checkIn(String ticketId) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/internal/tickets/{ticketId}/check-in")
                    .build(ticketId);
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.PUT,
                    authorizedEntity(),
                    String.class);
            CheckInData data = readData(response.getBody(), CheckInData.class);
            return data.result();
        } catch (HttpClientErrorException.NotFound ex) {
            return "INVALID_QR_TOKEN";
        } catch (HttpClientErrorException ex) {
            String code = errorCode(ex.getResponseBodyAsString());
            if (code != null) {
                return code;
            }
            throw downstreamUnavailable("e-ticket rejected check-in", ex);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            throw downstreamUnavailable("e-ticket unavailable during check-in", ex);
        }
    }

    public List<SnapshotTicket> getSnapshot(String concertId) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/internal/tickets/snapshot")
                    .queryParam("concertId", concertId)
                    .build()
                    .toUri();
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    authorizedEntity(),
                    String.class);
            JsonNode data = readDataNode(response.getBody());
            List<SnapshotTicket> tickets = new ArrayList<>();
            for (JsonNode ticket : data.path("tickets")) {
                tickets.add(new SnapshotTicket(
                        text(ticket, "ticketId"),
                        text(ticket, "qrToken"),
                        text(ticket, "eventId"),
                        text(ticket, "zoneId"),
                        text(ticket, "zoneName"),
                        text(ticket, "holderName"),
                        text(ticket, "status"),
                        parseInstant(text(ticket, "updatedAt"))));
            }
            return tickets;
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            throw downstreamUnavailable("e-ticket unavailable during snapshot generation", ex);
        } catch (HttpClientErrorException ex) {
            throw downstreamUnavailable("e-ticket rejected snapshot generation", ex);
        }
    }

    private HttpEntity<Void> authorizedEntity() {
        HttpHeaders headers = new HttpHeaders();
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
        return new HttpEntity<>(headers);
    }

    private <T> T readData(String body, Class<T> type) {
        try {
            return objectMapper.treeToValue(readDataNode(body), type);
        } catch (Exception ex) {
            throw downstreamUnavailable("Invalid e-ticket response body", ex);
        }
    }

    private JsonNode readDataNode(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("success").asBoolean(false) || root.path("data").isMissingNode()) {
                throw downstreamUnavailable("Unexpected e-ticket envelope", null);
            }
            return root.path("data");
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw downstreamUnavailable("Invalid e-ticket response envelope", ex);
        }
    }

    private String errorCode(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode code = root.path("error").path("code");
            return code.isMissingNode() ? null : code.asText();
        } catch (Exception ex) {
            return null;
        }
    }

    private ApiException downstreamUnavailable(String message, Exception cause) {
        ApiException apiException =
                new ApiException(ErrorCode.ETICKET_SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
        if (cause != null) {
            apiException.initCause(cause);
        }
        return apiException;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    public record TicketInfo(
            String id,
            String eventId,
            String status,
            String zoneId,
            String zoneName,
            String holderName
    ) {}

    public record SnapshotTicket(
            String ticketId,
            String qrToken,
            String eventId,
            String zoneId,
            String zoneName,
            String holderName,
            String status,
            Instant updatedAt
    ) {}

    private record CheckInData(String result, String ticketId) {}
}
