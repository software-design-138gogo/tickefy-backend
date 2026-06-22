package com.tickefy.payment.modules.payment.dto;

import java.time.Instant;
import java.util.UUID;

public record CreatePaymentResponse(
        UUID paymentId, String paymentUrl, String qrCodePayload, Instant expiresAt) {}
