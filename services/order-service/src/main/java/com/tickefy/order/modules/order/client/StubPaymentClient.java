package com.tickefy.order.modules.order.client;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payment.stub", havingValue = "true", matchIfMissing = true)
public class StubPaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentClient.class);

    @Override
    public PaymentResult createTransaction(CreatePaymentCommand cmd, String bearerToken) {
        String txId = UUID.randomUUID().toString();
        String paymentUrl = "https://pay.stub.local/" + txId;
        log.debug("StubPaymentClient: orderId={} amount={} txId={}", cmd.orderId(), cmd.amount(), txId);
        return new PaymentResult(txId, paymentUrl, "INITIATED");
    }

    @Override
    public RefundResponse refund(RefundRequest request) {
        UUID transactionId = UUID.randomUUID();
        String gatewayRef = "STUB-REFUND-" + UUID.randomUUID();
        log.debug("Stub refund: orderId={} amount={} txId={}", request.orderId(), request.amount(), transactionId);
        return new RefundResponse("REFUNDED", gatewayRef, transactionId);
    }
}
