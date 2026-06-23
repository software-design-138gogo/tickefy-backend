package com.tickefy.order.modules.order.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "app.payment.stub", havingValue = "false")
public class RealPaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(RealPaymentClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String paymentPath;

    public RealPaymentClient(
            @Value("${app.payment.base-url}") String baseUrl,
            @Value("${app.payment.internal-path:/internal/payments}") String paymentPath,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.paymentPath = paymentPath;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInitializer(req -> {
                    req.getHeaders().set("Connect-Timeout", "2000");
                })
                .build();
    }

    @Override
    public PaymentResult createTransaction(CreatePaymentCommand cmd, String bearerToken) {
        log.debug("RealPaymentClient: calling payment service orderId={} amount={}", cmd.orderId(), cmd.amount());
        try {
            byte[] responseBytes = restClient.post()
                    .uri(paymentPath)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .body(new PaymentRequestBody(
                            cmd.orderId().toString(),
                            cmd.userId().toString(),
                            cmd.amount(),
                            cmd.currency(),
                            cmd.idempotencyKey()))
                    .exchange((request, response) -> {
                        HttpStatusCode statusCode = response.getStatusCode();
                        byte[] body = response.getBody().readAllBytes();
                        if (statusCode.is2xxSuccessful()) {
                            return body;
                        }
                        handleError(statusCode, body);
                        return null; // unreachable
                    });

            return parsePaymentResult(responseBytes);
        } catch (PaymentUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException) {
                log.warn("Payment connect failed: {}", e.getMessage());
                throw new PaymentUnavailableException("Payment service unreachable", e);
            }
            log.warn("Payment HTTP error: {}", e.getMessage());
            throw new PaymentUnavailableException("Payment service error: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error calling payment service", e);
            throw new PaymentUnavailableException("Unexpected payment client error", e);
        }
    }

    private void handleError(HttpStatusCode statusCode, byte[] body) {
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        if (statusCode.is4xxClientError()) {
            log.warn("Payment rejected request: status={} body={}", statusCode.value(), bodyStr);
        } else {
            log.warn("Payment unavailable: status={} body={}", statusCode.value(), bodyStr);
        }
        throw new PaymentUnavailableException("Payment returned " + statusCode.value());
    }

    private PaymentResult parsePaymentResult(byte[] responseBytes) {
        JsonNode data;
        try {
            JsonNode root = objectMapper.readTree(responseBytes);
            data = root.path("data");
        } catch (Exception e) {
            log.error("Failed to parse payment response", e);
            throw new PaymentUnavailableException("Failed to parse payment response: " + e.getMessage());
        }
        String paymentId = data.path("paymentId").asText(null);
        if (paymentId == null || paymentId.isBlank()) {
            log.warn("Payment response missing paymentId, raw data={}", data);
            throw new PaymentUnavailableException("Payment response missing paymentId");
        }
        String paymentUrl = data.path("paymentUrl").asText(null);
        return new PaymentResult(paymentId, paymentUrl, "PENDING");
    }

    // Internal body record — serialized to JSON for payment service
    private record PaymentRequestBody(
            String orderId,
            String userId,
            long amount,
            String currency,
            String idempotencyKey) {}
}
