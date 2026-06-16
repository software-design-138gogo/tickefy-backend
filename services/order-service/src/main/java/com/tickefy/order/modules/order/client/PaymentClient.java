package com.tickefy.order.modules.order.client;

import java.util.UUID;

public interface PaymentClient {

    PaymentResult createTransaction(UUID orderId, long amount);
}
