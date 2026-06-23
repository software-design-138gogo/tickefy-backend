package com.tickefy.payment.modules.payment.gateway;

import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
import com.tickefy.payment.modules.payment.gateway.SePayClient.CreateQrResult;
import com.tickefy.payment.modules.payment.gateway.SePayClient.QueryStatusResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    private final SePayClient delegate;

    public PaymentGatewayClient(SePayClient delegate) {
        this.delegate = delegate;
    }

    @CircuitBreaker(name = "sepay", fallbackMethod = "createQrFallback")
    public CreateQrResult createQr(UUID paymentId, long amount, String currency, UUID orderId) {
        return delegate.createQr(paymentId, amount, currency, orderId);
    }

    CreateQrResult createQrFallback(
            UUID paymentId, long amount, String currency, UUID orderId, Throwable t) {
        log.warn(
                "SePay CB fallback paymentId={} cause={}",
                paymentId,
                t.toString());
        throw new ApiException(
                ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Payment gateway unavailable",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @CircuitBreaker(name = "sepay", fallbackMethod = "queryStatusFallback")
    public QueryStatusResult queryStatus(String key) {
        return delegate.queryStatus(key);
    }

    QueryStatusResult queryStatusFallback(String key, Throwable t) {
        log.warn("SePay CB queryStatus fallback key={} cause={}", key, t.toString());
        throw new PaymentGatewayException("queryStatus CB fallback: " + t.getMessage());
    }
}
