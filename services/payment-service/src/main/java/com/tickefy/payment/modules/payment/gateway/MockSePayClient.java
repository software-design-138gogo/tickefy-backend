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

    @Value("${app.sepay.mock.query-result:NONE}")
    private String queryResult;

    @Value("${app.sepay.mock.refund-mode:NONE}")
    private String refundMode;

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
        String resolvedStatus;
        if ("SUCCESS".equalsIgnoreCase(queryResult)) {
            resolvedStatus = "SUCCESS";
        } else if ("FAILED".equalsIgnoreCase(queryResult)) {
            resolvedStatus = "FAILED";
        } else {
            resolvedStatus = "PENDING";
        }
        String resolvedGatewayTxnId = (gatewayTransactionId != null)
                ? gatewayTransactionId
                : "MOCK-TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.debug(
                "MockSePay.queryStatus gatewayTransactionId={} query-result={} -> status={}",
                gatewayTransactionId,
                queryResult,
                resolvedStatus);
        return new QueryStatusResult(resolvedGatewayTxnId, resolvedStatus);
    }

    @Override
    public RefundResult refund(String gatewayTransactionId, long amount, String refundRequestId) {
        if ("REJECT".equalsIgnoreCase(refundMode)) {
            // Business decline (e.g. card closed) — return value, NOT exception → CB stays closed (R1).
            log.warn(
                    "MockSePay.refund refund-mode=REJECT gatewayTransactionId={} refundRequestId={}",
                    gatewayTransactionId,
                    refundRequestId);
            return new RefundResult("REJECTED", null, "card_locked");
        }
        if ("FAIL".equalsIgnoreCase(refundMode)) {
            log.warn("MockSePay.refund refund-mode=FAIL refundRequestId={}", refundRequestId);
            throw new PaymentGatewayException("mock forced refund failure");
        }
        if ("TIMEOUT".equalsIgnoreCase(refundMode)) {
            log.warn("MockSePay.refund refund-mode=TIMEOUT refundRequestId={} sleeping 35s", refundRequestId);
            try {
                Thread.sleep(35_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new PaymentGatewayException("mock refund timeout");
        }
        String gatewayRef = "MOCK-REFUND-" + refundRequestId;
        log.info(
                "MockSePay.refund OK gatewayTransactionId={} amount={} refundRequestId={} gatewayRef={}",
                gatewayTransactionId,
                amount,
                refundRequestId,
                gatewayRef);
        return new RefundResult("REFUNDED", gatewayRef, null);
    }
}
