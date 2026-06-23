package com.tickefy.payment.modules.payment.gateway;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockSePayClient implements SePayClient {

    private static final Logger log = LoggerFactory.getLogger(MockSePayClient.class);

    @Value("${app.sepay.mock.fail-mode:NONE}")
    private String failMode;

    @Override
    public CreateQrResult createQr(UUID paymentId, long amount, String currency, UUID orderId) {
        if ("FAIL".equalsIgnoreCase(failMode)) {
            log.warn("MockSePay.createQr fail-mode=FAIL paymentId={}", paymentId);
            throw new PaymentGatewayException("mock forced failure");
        }
        if ("TIMEOUT".equalsIgnoreCase(failMode)) {
            log.warn("MockSePay.createQr fail-mode=TIMEOUT paymentId={} sleeping 35s", paymentId);
            try {
                Thread.sleep(35_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new PaymentGatewayException("mock timeout");
        }
        String gatewayOrderId = "MOCK-" + paymentId;
        String qrCodePayload = "MOCKQR|" + orderId + "|" + amount;
        String paymentUrl = "https://pay.mock.local/" + paymentId;
        log.info(
                "MockSePay.createQr paymentId={} orderId={} amount={} gatewayOrderId={}",
                paymentId,
                orderId,
                amount,
                gatewayOrderId);
        return new CreateQrResult(gatewayOrderId, qrCodePayload, paymentUrl);
    }

    @Override
    public QueryStatusResult queryStatus(String gatewayTransactionId) {
        log.debug(
                "MockSePay.queryStatus gatewayTransactionId={} -> PENDING (stub)",
                gatewayTransactionId);
        return new QueryStatusResult(gatewayTransactionId, "PENDING");
    }
}
