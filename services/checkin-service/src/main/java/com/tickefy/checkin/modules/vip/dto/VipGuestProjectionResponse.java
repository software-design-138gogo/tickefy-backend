package com.tickefy.checkin.modules.vip.dto;

import java.util.UUID;

public record VipGuestProjectionResponse(
        String email,
        String fullName,
        UUID ticketTypeId,
        String ticketTypeName) {}
