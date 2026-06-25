package com.tickefy.checkin.modules.vip.dto;

import java.util.UUID;

public record VipGuestDto(
        String email,
        String fullName,
        UUID ticketTypeId,
        String ticketTypeName) {}
