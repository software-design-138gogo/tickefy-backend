package com.tickefy.checkin.modules.vip.mapper;

import com.tickefy.checkin.modules.vip.dto.VipGuestDto;
import com.tickefy.checkin.modules.vip.dto.VipGuestProjectionResponse;
import com.tickefy.checkin.modules.vip.dto.VipGuestSnapshotDto;
import com.tickefy.checkin.modules.vip.entity.VipGuestProjectionEntity;
import java.util.UUID;

public final class VipGuestMapper {

    private VipGuestMapper() {}

    public static VipGuestProjectionResponse toResponse(VipGuestProjectionEntity entity) {
        return new VipGuestProjectionResponse(
                entity.getEmail(),
                entity.getFullName(),
                entity.getTicketTypeId(),
                entity.getTicketTypeName());
    }

    public static VipGuestSnapshotDto toSnapshotDto(VipGuestProjectionEntity entity) {
        return new VipGuestSnapshotDto(
                entity.getEmail(),
                entity.getFullName(),
                entity.getTicketTypeName());
    }

    public static VipGuestProjectionEntity toEntity(UUID concertId, VipGuestDto dto) {
        return VipGuestProjectionEntity.builder()
                .concertId(concertId)
                .email(dto.email())
                .fullName(dto.fullName())
                .ticketTypeId(dto.ticketTypeId())
                .ticketTypeName(dto.ticketTypeName())
                .build();
    }
}
