package com.tickefy.inventory.modules.inventory.service;

import com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commit / release of a reservation, driven by order.* events (Pass 2).
 *
 * <p>Idempotent by reservation status guard: RESERVED → COMMITTED (commit) or RESERVED → RELEASED
 * (release); a re-delivered event finds the reservation already in the terminal state and is a no-op.
 * Each method is a single TX via the Spring proxy (called from the consumer, not self-invoked).
 */
@Service
public class ReservationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ReservationLifecycleService.class);

    private final TicketReservationRepository reservationRepository;
    private final TicketTypeInventoryRepository inventoryRepository;
    private final InventoryRedisService redisService;

    public ReservationLifecycleService(
            TicketReservationRepository reservationRepository,
            TicketTypeInventoryRepository inventoryRepository,
            InventoryRedisService redisService) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.redisService = redisService;
    }

    /** RESERVED → COMMITTED: reserved-=qty, sold+=qty (DB only; Redis stock already decremented at reserve). */
    @Transactional
    public void commit(UUID orderId, UUID ticketTypeId, int quantity) {
        TicketReservationEntity r = reservationRepository
                .findByOrderIdAndTicketTypeId(orderId, ticketTypeId).orElse(null);
        if (r == null) {
            log.error("commit: no reservation for orderId={} ticketTypeId={} — skipping (anomaly)", orderId, ticketTypeId);
            return;
        }
        if ("COMMITTED".equals(r.getStatus())) {
            log.info("commit skipped (idempotent) orderId={} ticketTypeId={} already COMMITTED", orderId, ticketTypeId);
            return;
        }
        if (!"RESERVED".equals(r.getStatus())) {
            log.warn("commit: reservation orderId={} ticketTypeId={} in status={} — cannot commit", orderId, ticketTypeId, r.getStatus());
            return;
        }
        int rows = inventoryRepository.commitReserved(ticketTypeId, quantity);
        if (rows == 0) {
            log.error("commit: DB guard rejected (reserved<qty) ticketTypeId={} qty={}", ticketTypeId, quantity);
            return;
        }
        r.setStatus("COMMITTED");
        reservationRepository.save(r);
        log.info("Reservation COMMITTED orderId={} ticketTypeId={} qty={}", orderId, ticketTypeId, quantity);
    }

    /** RESERVED → RELEASED: reserved-=qty (DB) + Redis stock+=qty, user-limit-=qty (return to pool). */
    @Transactional
    public void release(UUID orderId, UUID ticketTypeId, int quantity) {
        TicketReservationEntity r = reservationRepository
                .findByOrderIdAndTicketTypeId(orderId, ticketTypeId).orElse(null);
        if (r == null) {
            log.error("release: no reservation for orderId={} ticketTypeId={} — skipping (anomaly)", orderId, ticketTypeId);
            return;
        }
        if ("RELEASED".equals(r.getStatus())) {
            log.info("release skipped (idempotent) orderId={} ticketTypeId={} already RELEASED", orderId, ticketTypeId);
            return;
        }
        if ("COMMITTED".equals(r.getStatus())) {
            log.warn("release: reservation orderId={} ticketTypeId={} already COMMITTED (sold) — NOT releasing", orderId, ticketTypeId);
            return;
        }
        int rows = inventoryRepository.releaseReserved(ticketTypeId, quantity);
        if (rows == 0) {
            log.error("release: DB guard rejected (reserved<qty) ticketTypeId={} qty={}", ticketTypeId, quantity);
            return;
        }
        r.setStatus("RELEASED");
        reservationRepository.save(r);
        // Return stock + per-user quota to Redis (same compensation as a failed reserve).
        redisService.compensateReserve(ticketTypeId, r.getUserId(), quantity);
        log.info("Reservation RELEASED orderId={} ticketTypeId={} qty={}", orderId, ticketTypeId, quantity);
    }
}
