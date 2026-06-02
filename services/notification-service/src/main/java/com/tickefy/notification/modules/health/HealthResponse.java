package com.tickefy.notification.modules.health;

import java.time.Instant;

public record HealthResponse(String status, String service, Instant timestamp) {}
