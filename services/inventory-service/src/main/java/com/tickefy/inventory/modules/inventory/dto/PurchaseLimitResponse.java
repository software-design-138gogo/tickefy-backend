package com.tickefy.inventory.modules.inventory.dto;

import java.util.UUID;

public record PurchaseLimitResponse(UUID ticketTypeId, Integer perUserLimit, Integer alreadyOwned, Integer remaining) {}
