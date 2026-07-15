package com.tickefy.checkin.modules.vip.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.modules.vip.dto.VipGuestDto;
import com.tickefy.checkin.modules.vip.exception.CsvUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * Typed HTTP client to csv-ingestion-service VIP guest endpoint.
 * Mirrors ETicketClient pattern: authorizedEntity() + readDataNode envelope unwrap.
 * HTTP calls MUST remain outside any @Transactional boundary (see §8).
 * PII (email, fullName) MUST NOT be logged (see §15).
 */
@Component
public class CsvVipClient {

    private static final Logger log = LoggerFactory.getLogger(CsvVipClient.class);
    private static final int PAGE_SIZE = 200;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public CsvVipClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${csv.service.url:http://localhost:8090}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /**
     * Fetches all VIP guests for a concert by paging through the csv-ingestion API.
     * HTTP is invoked outside any transaction.
     *
     * @throws CsvUnavailableException on infrastructure failure (5xx, timeout, parse error)
     * @throws IllegalStateException   if called without an active HTTP request context
     */
    public List<VipGuestDto> fetchAll(UUID concertId) {
        List<VipGuestDto> result = new ArrayList<>();
        int page = 0;
        while (true) {
            JsonNode data = fetchPage(concertId, page);
            JsonNode content = data.path("content");
            if (!content.isArray()) {
                throw new CsvUnavailableException(
                        "csv-ingestion returned unexpected content for concertId=" + concertId);
            }
            for (JsonNode item : content) {
                String email = text(item, "email");
                String fullName = text(item, "fullName");
                UUID ticketTypeId = uuid(item, "ticketTypeId");
                String ticketTypeName = text(item, "ticketTypeName");
                result.add(new VipGuestDto(email, fullName, ticketTypeId, ticketTypeName));
            }
            int totalPages = data.path("totalPages").asInt(1);
            int number = data.path("number").asInt(0);
            if (number + 1 >= totalPages) {
                break;
            }
            page++;
        }
        log.debug("CsvVipClient.fetchAll concertId={} totalRows={}", concertId, result.size());
        return result;
    }

    private JsonNode fetchPage(UUID concertId, int page) {
        try {
            String uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/internal/concerts/{concertId}/vip-guests")
                    .queryParam("page", page)
                    .queryParam("size", PAGE_SIZE)
                    .buildAndExpand(concertId)
                    .toUriString();
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    authorizedEntity(),
                    String.class);
            return readDataNode(response.getBody(), concertId);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            throw new CsvUnavailableException(
                    "csv-ingestion unavailable for concertId=" + concertId, ex);
        } catch (HttpClientErrorException ex) {
            throw new CsvUnavailableException(
                    "csv-ingestion rejected request for concertId=" + concertId, ex);
        }
    }

    private HttpEntity<Void> authorizedEntity() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new IllegalStateException("CsvVipClient requires request auth context");
        }
        HttpHeaders headers = new HttpHeaders();
        String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return new HttpEntity<>(headers);
    }

    private JsonNode readDataNode(String body, UUID concertId) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("success").asBoolean(false) || root.path("data").isMissingNode()) {
                throw new CsvUnavailableException(
                        "Unexpected csv-ingestion envelope for concertId=" + concertId);
            }
            return root.path("data");
        } catch (CsvUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CsvUnavailableException(
                    "Failed to parse csv-ingestion response for concertId=" + concertId, ex);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private UUID uuid(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
