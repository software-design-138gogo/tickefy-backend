package com.tickefy.inventory.modules.inventory.dto;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(UUID reservationId, UUID ticketTypeId, Integer quantity, Instant expiresAt) {}
