package com.tickefy.inventory.modules.health;

import java.time.Instant;

public record HealthResponse(String status, String service, Instant timestamp) {}
