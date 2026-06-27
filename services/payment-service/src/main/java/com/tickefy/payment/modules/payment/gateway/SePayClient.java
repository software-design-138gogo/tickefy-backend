package com.tickefy.payment.modules.payment.gateway;

import java.util.UUID;

public interface SePayClient {

    CreateQrResult createQr(UUID paymentId, long amount, String currency, UUID orderId);

    QueryStatusResult queryStatus(String gatewayTransactionId);

    /**
     * Refund a settled payment (mảnh [3]).
     *
     * <p>Returns a {@link RefundResult}: status REFUNDED (success) or REJECTED (business decline —
     * e.g. card closed). Throws {@link PaymentGatewayException} ONLY for retryable technical
     * failures (down/timeout) so the circuit breaker records them; a REJECTED business outcome is a
     * return value and must NOT throw (else it would trip the breaker — R1).
     */
    RefundResult refund(String gatewayTransactionId, long amount, String refundRequestId);

    record CreateQrResult(String gatewayOrderId, String qrCodePayload, String paymentUrl) {}

    record QueryStatusResult(String gatewayTransactionId, String status) {}

    record RefundResult(String status, String gatewayRef, String reason) {} // status = REFUNDED | REJECTED
}
