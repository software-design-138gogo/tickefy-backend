package com.tickefy.order.modules.order.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.order.common.exception.ErrorCode;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String reservationsPath;

    public InventoryClient(
            @Value("${app.inventory.base-url}") String baseUrl,
            @Value("${app.inventory.reservations-path}") String reservationsPath,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.reservationsPath = reservationsPath;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInitializer(req -> {
                    req.getHeaders().set("Connect-Timeout", "2000");
                })
                .build();
    }

    /**
     * POST /api/inventory/reservations — forward caller's Bearer token.
     * Throws InventoryBusinessException for 409/422/403 (order → CANCELLED).
     * Throws InventoryUnavailableException for 5xx / timeout / connect error (order stays CREATED).
     */
    public ReservationResult reserve(ReserveClientRequest req, String bearerToken) {
        log.debug("Calling inventory reserve: orderId={}, ticketTypeId={}, qty={}",
                req.orderId(), req.ticketTypeId(), req.quantity());
        try {
            byte[] responseBytes = restClient.post()
                    .uri(reservationsPath)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .body(req)
                    .exchange((request, response) -> {
                        HttpStatusCode statusCode = response.getStatusCode();
                        byte[] body = response.getBody().readAllBytes();
                        if (statusCode.is2xxSuccessful()) {
                            return body;
                        }
                        // error path
                        handleErrorResponse(statusCode, body);
                        return null; // unreachable
                    });

            return parseReservationResult(responseBytes);
        } catch (InventoryBusinessException | InventoryUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException) {
                log.warn("Inventory connect failed: {}", e.getMessage());
                throw new InventoryUnavailableException("Inventory service unreachable", e);
            }
            log.warn("Inventory HTTP error: {}", e.getMessage());
            throw new InventoryUnavailableException("Inventory service error: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error calling inventory", e);
            throw new InventoryUnavailableException("Unexpected inventory client error", e);
        }
    }

    private void handleErrorResponse(HttpStatusCode statusCode, byte[] body) {
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        log.debug("Inventory error response status={} body={}", statusCode.value(), bodyStr);

        if (statusCode.is5xxServerError()) {
            throw new InventoryUnavailableException("Inventory returned " + statusCode.value());
        }

        // 4xx — parse error code
        String errorCode = extractErrorCode(bodyStr);
        Object details = extractDetails(bodyStr);

        if ("TICKET_SOLD_OUT".equals(errorCode)) {
            throw new InventoryBusinessException(
                    ErrorCode.TICKET_SOLD_OUT, "Ticket sold out", HttpStatus.CONFLICT, null);
        } else if ("PER_USER_LIMIT_EXCEEDED".equals(errorCode)) {
            throw new InventoryBusinessException(
                    ErrorCode.PER_USER_LIMIT_EXCEEDED, "Per-user limit exceeded", HttpStatus.UNPROCESSABLE_ENTITY, details);
        } else if ("SALE_WINDOW_CLOSED".equals(errorCode)) {
            throw new InventoryBusinessException(
                    ErrorCode.SALE_WINDOW_CLOSED, "Sale window closed", HttpStatus.FORBIDDEN, null);
        } else {
            // Unknown 4xx — treat as unavailable to be safe
            throw new InventoryUnavailableException("Inventory returned unexpected 4xx: " + statusCode.value() + " code=" + errorCode);
        }
    }

    private String extractErrorCode(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                return errorNode.path("code").asText(null);
            }
        } catch (Exception e) {
            log.debug("Failed to parse inventory error body", e);
        }
        return null;
    }

    private Object extractDetails(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode detailsNode = root.path("error").path("details");
            if (!detailsNode.isMissingNode() && !detailsNode.isNull()) {
                return objectMapper.treeToValue(detailsNode, Object.class);
            }
        } catch (Exception e) {
            log.debug("Failed to parse inventory error details", e);
        }
        return null;
    }

    private ReservationResult parseReservationResult(byte[] responseBytes) {
        try {
            JsonNode root = objectMapper.readTree(responseBytes);
            JsonNode data = root.path("data");
            UUID reservationId = UUID.fromString(data.path("reservationId").asText());
            long unitPrice = data.path("unitPrice").asLong();
            long totalAmount = data.path("totalAmount").asLong();
            Instant expiresAt = Instant.parse(data.path("expiresAt").asText());
            String ticketTypeName = data.path("ticketTypeName").asText(null);
            return new ReservationResult(reservationId, unitPrice, totalAmount, expiresAt, ticketTypeName);
        } catch (Exception e) {
            log.error("Failed to parse inventory reservation response", e);
            throw new InventoryUnavailableException("Failed to parse inventory response: " + e.getMessage());
        }
    }
}
