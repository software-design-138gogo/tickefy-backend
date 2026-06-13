package com.tickefy.inventory.modules.inventory.service;

import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.PurchaseLimitResponse;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseLimitService {

    private final TicketTypeRepository ticketTypeRepository;
    private final TicketReservationRepository reservationRepository;

    public PurchaseLimitService(
            TicketTypeRepository ticketTypeRepository,
            TicketReservationRepository reservationRepository) {
        this.ticketTypeRepository = ticketTypeRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional(readOnly = true)
    public PurchaseLimitResponse get(UUID userId, UUID ticketTypeId) {
        TicketTypeEntity tt = ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Ticket type not found", HttpStatus.NOT_FOUND));

        Integer perUserLimit = tt.getPerUserLimit();
        int alreadyOwned = reservationRepository.sumActiveQuantity(userId, ticketTypeId);

        Integer remaining = perUserLimit == null ? null : Math.max(0, perUserLimit - alreadyOwned);

        return new PurchaseLimitResponse(ticketTypeId, perUserLimit, alreadyOwned, remaining);
    }
}
