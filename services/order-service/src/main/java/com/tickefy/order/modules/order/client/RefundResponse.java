package com.tickefy.order.modules.order.client;

import java.util.UUID;

public record RefundResponse(String status, String refundGatewayRef, UUID paymentTransactionId) {}
