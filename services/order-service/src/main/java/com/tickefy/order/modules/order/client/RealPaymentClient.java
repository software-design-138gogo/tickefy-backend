package com.tickefy.order.modules.order.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "app.payment.stub", havingValue = "false")
public class RealPaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(RealPaymentClient.class);

    private final RestClient restClient;
    private final RestClient refundRestClient;
    private final ObjectMapper objectMapper;
    private final String paymentPath;
    private final String refundPath;

    /** Kept for focused unit tests that construct the adapter directly. */
    public RealPaymentClient(String baseUrl, String paymentPath, ObjectMapper objectMapper) {
        this(baseUrl, paymentPath, "/internal/payments/refund", Duration.ofSeconds(2), Duration.ofSeconds(30), objectMapper);
    }

    @Autowired
    public RealPaymentClient(
            @Value("${app.payment.base-url}") String baseUrl,
            @Value("${app.payment.internal-path:/internal/payments}") String paymentPath,
            @Value("${app.payment.refund-path:/internal/payments/refund}") String refundPath,
            @Value("${app.payment.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${app.payment.read-timeout:PT30S}") Duration readTimeout,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.paymentPath = paymentPath;
        this.refundPath = refundPath;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.refundRestClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
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
                        handleCreateError(statusCode, body);
                        return null;
                    });
            return parsePaymentResult(responseBytes);
        } catch (PaymentUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw unavailable("Payment service error", e);
        } catch (Exception e) {
            log.error("Unexpected error calling payment service", e);
            throw new PaymentUnavailableException("Unexpected payment client error", e);
        }
    }

    /** Public interface method so Spring's Resilience4j proxy intercepts refund calls. */
    @Override
    @CircuitBreaker(name = "paymentRefund")
    public RefundResponse refund(RefundRequest request) {
        log.debug("Refund request orderId={} amount={}", request.orderId(), request.amount());
        try {
            byte[] responseBytes = refundRestClient.post()
                    .uri(refundPath)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        byte[] body = response.getBody().readAllBytes();
                        if (status.is2xxSuccessful()) {
                            return body;
                        }
                        handleRefundError(status, body);
                        return null;
                    });
            return parseRefundResponse(responseBytes);
        } catch (PaymentRefundException | PaymentUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw unavailable("Payment refund transport error", e);
        } catch (Exception e) {
            log.warn("Unexpected payment refund error orderId={}: {}", request.orderId(), e.getMessage());
            throw new PaymentUnavailableException("Unexpected payment refund error", e);
        }
    }

    private void handleCreateError(HttpStatusCode statusCode, byte[] body) {
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        log.warn("Payment create failed: status={} body={}", statusCode.value(), bodyStr);
        throw new PaymentUnavailableException("Payment returned " + statusCode.value());
    }

    private void handleRefundError(HttpStatusCode status, byte[] body) {
        String errorCode = null;
        String message = "Payment refund returned " + status.value();
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            errorCode = error.path("code").asText(null);
            message = error.path("message").asText(message);
        } catch (Exception e) {
            log.warn("Unable to parse refund error envelope status={}", status.value());
        }
        if (status.is5xxServerError()) {
            throw new PaymentUnavailableException(message);
        }
        throw new PaymentRefundException(status.value(), errorCode, message);
    }

    private PaymentResult parsePaymentResult(byte[] responseBytes) {
        JsonNode data;
        try {
            data = objectMapper.readTree(responseBytes).path("data");
        } catch (Exception e) {
            throw new PaymentUnavailableException("Failed to parse payment response", e);
        }
        String paymentId = data.path("paymentId").asText(null);
        if (paymentId == null || paymentId.isBlank()) {
            throw new PaymentUnavailableException("Payment response missing paymentId");
        }
        return new PaymentResult(paymentId, data.path("paymentUrl").asText(null), "PENDING");
    }

    private RefundResponse parseRefundResponse(byte[] responseBytes) {
        try {
            JsonNode data = objectMapper.readTree(responseBytes).path("data");
            String status = data.path("status").asText(null);
            String gatewayRef = data.path("refundGatewayRef").asText(null);
            String transactionId = data.path("paymentTransactionId").asText(null);
            if (!"REFUNDED".equals(status)
                    || gatewayRef == null
                    || gatewayRef.isBlank()
                    || transactionId == null
                    || transactionId.isBlank()) {
                throw new PaymentUnavailableException("Malformed successful refund response");
            }
            return new RefundResponse(status, gatewayRef, UUID.fromString(transactionId));
        } catch (PaymentUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentUnavailableException("Failed to parse payment refund response", e);
        }
    }

    private PaymentUnavailableException unavailable(String message, RestClientException e) {
        if (e.getCause() instanceof ConnectException) {
            log.warn("Payment connect failed: {}", e.getMessage());
        } else {
            log.warn("{}: {}", message, e.getMessage());
        }
        return new PaymentUnavailableException(message, e);
    }

    private record PaymentRequestBody(
            String orderId,
            String userId,
            long amount,
            String currency,
            String idempotencyKey) {}
}
