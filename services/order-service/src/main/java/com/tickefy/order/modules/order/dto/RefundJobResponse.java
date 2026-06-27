package com.tickefy.order.modules.order.dto;

import java.time.Instant;
import java.util.UUID;

public record RefundJobResponse(UUID concertId, String status, Instant enabledAt) {}
