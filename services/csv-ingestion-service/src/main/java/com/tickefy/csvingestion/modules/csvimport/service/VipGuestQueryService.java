package com.tickefy.csvingestion.modules.csvimport.service;

import com.tickefy.csvingestion.modules.csvimport.dto.VipGuestResponse;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only query for the internal VIP-guests endpoint (6a). */
@Service
public class VipGuestQueryService {

    private final VipGuestRepository vipGuestRepository;

    public VipGuestQueryService(VipGuestRepository vipGuestRepository) {
        this.vipGuestRepository = vipGuestRepository;
    }

    @Transactional(readOnly = true)
    public Page<VipGuestResponse> getVipGuests(UUID concertId, String email, Pageable pageable) {
        Page<VipGuestEntity> page = (email == null || email.isBlank())
                ? vipGuestRepository.findByConcertId(concertId, pageable)
                // emails are stored normalized lowercase at import (4b dedup) — match the filter likewise
                : vipGuestRepository.findByConcertIdAndEmail(concertId, email.trim().toLowerCase(), pageable);
        return page.map(this::toResponse);
    }

    private VipGuestResponse toResponse(VipGuestEntity e) {
        return new VipGuestResponse(e.getEmail(), e.getFullName(), e.getTicketTypeId(), e.getTicketTypeName());
    }
}
