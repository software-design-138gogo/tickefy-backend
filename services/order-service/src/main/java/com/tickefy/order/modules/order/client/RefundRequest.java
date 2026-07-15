package com.tickefy.order.modules.order.client;

import java.util.UUID;

public record RefundRequest(UUID orderId, String refundRequestId, long amount) {}
