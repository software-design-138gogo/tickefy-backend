package com.tickefy.inventory.modules.inventory.dto;

import java.util.UUID;

public record AvailabilityResponse(UUID ticketTypeId, Integer available, String status) {}
