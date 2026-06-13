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
    public PaymentResult createTransaction(UUID orderId, long amount) {
        String transactionId = UUID.randomUUID().toString();
        String paymentUrl = "https://pay.stub.local/" + transactionId;
        log.debug("StubPaymentClient: orderId={} amount={} txId={}", orderId, amount, transactionId);
        return new PaymentResult(transactionId, paymentUrl, "INITIATED");
    }
}
