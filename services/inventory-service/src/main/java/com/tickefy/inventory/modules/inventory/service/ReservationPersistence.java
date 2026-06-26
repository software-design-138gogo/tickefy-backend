package com.tickefy.inventory.modules.inventory.service;

import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.ReservationResponse;
import com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence bean extracted from ReservationService to ensure @Transactional methods are called
 * via Spring AOP proxy (avoids self-invocation bypass that caused InvalidDataAccessApiUsageException
 * when @Modifying queries ran outside a transaction).
 */
@Service
public class ReservationPersistence {

    private static final Logger log = LoggerFactory.getLogger(ReservationPersistence.class);

    private final TicketReservationRepository reservationRepository;
    private final TicketTypeInventoryRepository inventoryRepository;

    @Value("${app.inventory.reservation-ttl:PT15M}")
    private Duration reservationTtl;

    public ReservationPersistence(
            TicketReservationRepository reservationRepository,
            TicketTypeInventoryRepository inventoryRepository) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Redis path: atomic conditional UPDATE then INSERT reservation.
     * Called via proxy from ReservationService — @Transactional applies correctly.
     *
     * @throws ApiException TICKET_SOLD_OUT if DB guard rejects (Redis/DB divergence)
     * @throws DataIntegrityViolationException propagated to caller for idempotent duplicate handling
     */
    @Transactional
    public ReservationResponse writeReservationToDb(
            UUID ticketTypeId, UUID userId, UUID orderId, int qty, long unitPrice) {

        int rows = inventoryRepository.incrementReservedConditional(ticketTypeId, qty);
        if (rows == 0) {
            log.warn(
                    "DB capacity guard rejected reserve after Lua success: ticketTypeId={} qty={} — Redis/DB divergence",
                    ticketTypeId, qty);
            throw new ApiException(
                    ErrorCode.TICKET_SOLD_OUT, "DB capacity guard: no stock remaining", HttpStatus.CONFLICT);
        }

        Instant expiresAt = Instant.now().plus(reservationTtl);
        TicketReservationEntity reservation = TicketReservationEntity.builder()
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .orderId(orderId)
                .quantity(qty)
                .status("RESERVED")
                .expiresAt(expiresAt)
                .build();
        reservation = reservationRepository.save(reservation);
        return toResponse(reservation, unitPrice);
    }

    /**
     * DB-fallback path (Redis down): per-user limit check + conditional UPDATE + INSERT.
     * Called via proxy from ReservationService — @Transactional applies correctly.
     *
     * @param perUserLimit -1 means unlimited
     * @throws DataIntegrityViolationException caught internally; returns existing reservation
     */
    @Transactional
    public ReservationResponse writeReservationFallback(
            UUID ticketTypeId, UUID userId, UUID orderId, int qty, int perUserLimit, long unitPrice) {

        if (perUserLimit >= 0) {
            int owned = reservationRepository.sumActiveQuantity(userId, ticketTypeId);
            if (owned + qty > perUserLimit) {
                int remaining = Math.max(0, perUserLimit - owned);
                throw new ApiException(
                        ErrorCode.PER_USER_LIMIT_EXCEEDED,
                        "Per-user limit exceeded",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        Map.of("perUserLimit", perUserLimit, "alreadyOwned", owned, "remaining", remaining));
            }
        }

        int rows = inventoryRepository.incrementReservedConditional(ticketTypeId, qty);
        if (rows == 0) {
            throw new ApiException(ErrorCode.TICKET_SOLD_OUT, "Tickets are sold out", HttpStatus.CONFLICT);
        }

        Instant expiresAt = Instant.now().plus(reservationTtl);
        TicketReservationEntity reservation = TicketReservationEntity.builder()
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .orderId(orderId)
                .quantity(qty)
                .status("RESERVED")
                .expiresAt(expiresAt)
                .build();
        try {
            reservation = reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            log.info(
                    "Duplicate reservation (fallback path) for orderId={} ticketTypeId={}. Returning existing.",
                    orderId, ticketTypeId);
            return reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId)
                    .map(r -> toResponse(r, unitPrice))
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INTERNAL_SERVER_ERROR, "Reservation state inconsistent",
                            HttpStatus.INTERNAL_SERVER_ERROR));
        }
        return toResponse(reservation, unitPrice);
    }

    public ReservationResponse toResponse(TicketReservationEntity entity, long unitPrice) {
        long totalAmount = unitPrice * entity.getQuantity();
        return new ReservationResponse(
                entity.getId(),
                entity.getTicketTypeId(),
                entity.getQuantity(),
                unitPrice,
                totalAmount,
                entity.getExpiresAt(),
                null); // ticketTypeName enriched by ReservationService.reserve wrapper (PK lookup)
    }
}
