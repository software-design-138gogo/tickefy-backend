package com.tickefy.csvingestion.modules.csvimport.dto;

import java.util.UUID;

/**
 * Internal VIP guest projection (PII — internal-only endpoint, never gateway-exposed).
 * Exposes only the fields a consumer (checkin) needs; no internal id / importJobId / timestamps.
 */
public record VipGuestResponse(String email, String fullName, UUID ticketTypeId, String ticketTypeName) {}
