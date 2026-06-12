package com.tickefy.checkin.infrastructure.clients;

import com.tickefy.checkin.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP client to e-ticket-service.
 * Calls:
 *  - GET /api/tickets/by-token/{token}   → lookup QR token
 *  - PUT /api/tickets/{id}/check-in       → atomic mark checked-in
 */
@Component
public class ETicketClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ETicketClient(RestTemplate restTemplate,
                         @Value("${eticket.service.url:http://localhost:8087}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /**
     * Lookup ticket by QR token.
     * Returns the raw data map from the ApiResponse envelope.
     * Returns null if not found (INVALID_QR_TOKEN).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTicketByToken(String token) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    baseUrl + "/api/tickets/by-token/" + token,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );
            Map<String, Object> body = response.getBody();
            if (body != null && Boolean.TRUE.equals(body.get("success"))) {
                return (Map<String, Object>) body.get("data");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Atomic check-in call.
     * Returns "ACCEPTED" or "DUPLICATE_REJECTED" or null on error.
     */
    @SuppressWarnings("unchecked")
    public String checkIn(String ticketId) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    baseUrl + "/api/tickets/" + ticketId + "/check-in",
                    HttpMethod.PUT,
                    null,
                    new ParameterizedTypeReference<>() {}
            );
            Map<String, Object> body = response.getBody();
            if (body != null && Boolean.TRUE.equals(body.get("success"))) {
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                return data != null ? (String) data.get("result") : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
