package com.tickefy.payment.modules.payment.gateway;

import java.util.UUID;

public interface SePayClient {

    CreateQrResult createQr(UUID paymentId, long amount, String currency, UUID orderId);

    QueryStatusResult queryStatus(String gatewayTransactionId);

    record CreateQrResult(String gatewayOrderId, String qrCodePayload, String paymentUrl) {}

    record QueryStatusResult(String gatewayTransactionId, String status) {}
}
