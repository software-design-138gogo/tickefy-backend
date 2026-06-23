package com.tickefy.order.modules.order.client;

public interface PaymentClient {

    PaymentResult createTransaction(CreatePaymentCommand cmd, String bearerToken);
}
