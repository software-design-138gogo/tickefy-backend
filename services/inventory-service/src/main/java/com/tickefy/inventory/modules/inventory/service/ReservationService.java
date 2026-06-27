package com.tickefy.inventory.modules.inventory.service;

import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.ReservationResponse;
import com.tickefy.inventory.modules.inventory.dto.ReserveRequest;
import com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeInventoryEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final TicketReservationRepository reservationRepository;
    private final TicketTypeInventoryRepository inventoryRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final InventoryRedisService redisService;
    private final ReservationPersistence persistence;

    public ReservationService(
            TicketReservationRepository reservationRepository,
            TicketTypeInventoryRepository inventoryRepository,
            TicketTypeRepository ticketTypeRepository,
            InventoryRedisService redisService,
            ReservationPersistence persistence) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.redisService = redisService;
        this.persistence = persistence;
    }

    /**
     * Public entry: run reservation, then enrich response with ticketTypeName so order can carry the
     * loss-less name into OrderPaid/TicketsIssued (FE shows ticket type name). Name is resolved once
     * via PK lookup (cheap) because the Redis fast-path uses cached meta which does not hold the name.
     */
    public ReservationResponse reserve(ReserveRequest req) {
        ReservationResponse base = reserveInternal(req);
        String ticketTypeName = ticketTypeRepository.findById(req.ticketTypeId())
                .map(TicketTypeEntity::getName)
                .orElse(null);
        return new ReservationResponse(
                base.reservationId(),
                base.ticketTypeId(),
                base.quantity(),
                base.unitPrice(),
                base.totalAmount(),
                base.expiresAt(),
                ticketTypeName);
    }

    // Non-transactional orchestrator: idempotent step0, meta, sale-window, stock, Lua, compensation.
    private ReservationResponse reserveInternal(ReserveRequest req) {
        UUID ticketTypeId = req.ticketTypeId();
        UUID userId = req.userId();
        UUID orderId = req.orderId();
        int qty = req.quantity();

        // Step 0 [M1]: idempotent check — if reservation already exists, return it
        Optional<TicketReservationEntity> existing =
                reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId);
        if (existing.isPresent()) {
            log.info(
                    "Idempotent reserve: found existing reservation for orderId={} ticketTypeId={}",
                    orderId, ticketTypeId);
            // M1: still return correct price — load meta (Redis cache, DB only if missing) for unitPrice
            long unitPrice = ensureMetaLoaded(ticketTypeId).price();
            return persistence.toResponse(existing.get(), unitPrice);
        }

        // Step 1 [M2]: load meta (perUserLimit, saleStartAt, saleEndAt) from Redis or DB
        TicketTypeMetaHolder meta = ensureMetaLoaded(ticketTypeId);

        // Step 2: sale-window check (server time)
        Instant now = Instant.now();
        if (now.isBefore(meta.saleStartAt())) {
            throw new ApiException(ErrorCode.SALE_WINDOW_CLOSED, "Sale has not started yet", HttpStatus.FORBIDDEN);
        }
        if (now.isAfter(meta.saleEndAt())) {
            throw new ApiException(ErrorCode.SALE_WINDOW_CLOSED, "Sale has ended", HttpStatus.FORBIDDEN);
        }

        // Step 2.5: concert-cancelled guard (DB authoritative, fail-fast BEFORE stock check).
        // Cancellation is NOT cached in Redis meta — emergency stop-sale must read the DB source of truth.
        TicketTypeEntity tt = ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Ticket type not found", HttpStatus.NOT_FOUND));
        if (tt.isConcertCancelled()) {
            throw new ApiException(ErrorCode.CONCERT_CANCELLED, "Concert has been cancelled", HttpStatus.CONFLICT);
        }

        // Step 3 [M3]: ensure stock key exists, then EVALSHA
        ensureStockLoaded(ticketTypeId);

        // Try Redis path
        boolean redisAvailable = true;
        long result = -2L;
        try {
            result = redisService.executeReserve(ticketTypeId, userId, qty, meta.perUserLimit());
        } catch (Exception e) {
            log.warn(
                    "Redis unavailable during EVALSHA for ticketTypeId={}, falling back to DB path",
                    ticketTypeId, e);
            redisAvailable = false;
        }

        if (redisAvailable) {
            return handleRedisResult(result, meta, ticketTypeId, userId, orderId, qty);
        } else {
            return handleFallbackDb(meta, ticketTypeId, userId, orderId, qty);
        }
    }

    private ReservationResponse handleRedisResult(
            long result,
            TicketTypeMetaHolder meta,
            UUID ticketTypeId, UUID userId, UUID orderId, int qty) {

        if (result == -1L) {
            int owned = redisService.getUserOwned(userId, ticketTypeId);
            int remaining = Math.max(0, meta.perUserLimit() - owned);
            throw new ApiException(
                    ErrorCode.PER_USER_LIMIT_EXCEEDED,
                    "Per-user limit exceeded",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("perUserLimit", meta.perUserLimit(), "alreadyOwned", owned, "remaining", remaining));
        }
        if (result == -2L) {
            throw new ApiException(ErrorCode.TICKET_SOLD_OUT, "Tickets are sold out", HttpStatus.CONFLICT);
        }

        // result == 1: Lua succeeded — write to DB via proxy bean (TX applies)
        try {
            return persistence.writeReservationToDb(ticketTypeId, userId, orderId, qty, meta.price());
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(order_id, ticket_type_id) violated — inner TX already rolled back.
            // Compensate Redis, then re-read existing (outer is non-transactional, fresh query).
            log.info(
                    "Duplicate reservation constraint for orderId={} ticketTypeId={}. Compensating Redis and returning existing.",
                    orderId, ticketTypeId);
            redisService.compensateReserve(ticketTypeId, userId, qty);
            return reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId)
                    .map(r -> persistence.toResponse(r, meta.price()))
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INTERNAL_SERVER_ERROR,
                            "Reservation state inconsistent",
                            HttpStatus.INTERNAL_SERVER_ERROR));
        } catch (Exception e) {
            log.error(
                    "DB write failed after Lua reserve for ticketTypeId={}, compensating Redis",
                    ticketTypeId, e);
            redisService.compensateReserve(ticketTypeId, userId, qty);
            throw new ApiException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to persist reservation", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Fallback path when Redis is down: delegate entirely to DB via proxy bean (TX applies).
     */
    private ReservationResponse handleFallbackDb(
            TicketTypeMetaHolder meta,
            UUID ticketTypeId, UUID userId, UUID orderId, int qty) {

        return persistence.writeReservationFallback(
                ticketTypeId, userId, orderId, qty, meta.perUserLimit(), meta.price());
    }

    /**
     * M2/M3: load meta from Redis; if missing, rebuild from DB.
     */
    private TicketTypeMetaHolder ensureMetaLoaded(UUID ticketTypeId) {
        Map<Object, Object> redisMeta = redisService.getMeta(ticketTypeId);
        if (redisMeta != null && !redisMeta.isEmpty()) {
            try {
                int perUserLimit = Integer.parseInt((String) redisMeta.get("perUserLimit"));
                long price = Long.parseLong((String) redisMeta.get("price"));
                long startMs = Long.parseLong((String) redisMeta.get("saleStartAt"));
                long endMs = Long.parseLong((String) redisMeta.get("saleEndAt"));
                return new TicketTypeMetaHolder(
                        perUserLimit, price, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs));
            } catch (Exception e) {
                log.warn(
                        "Failed to parse Redis meta for ticketTypeId={}, rebuilding from DB",
                        ticketTypeId, e);
            }
        }

        // M3: rebuild from DB
        TicketTypeEntity tt = ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Ticket type not found", HttpStatus.NOT_FOUND));
        int perUserLimit = tt.getPerUserLimit() == null ? -1 : tt.getPerUserLimit();
        redisService.seedMeta(
                ticketTypeId, tt.getPerUserLimit(), tt.getPrice(), tt.getSaleStartAt(), tt.getSaleEndAt());
        log.debug("M3 rebuilt meta from DB for ticketTypeId={}", ticketTypeId);
        return new TicketTypeMetaHolder(perUserLimit, tt.getPrice(), tt.getSaleStartAt(), tt.getSaleEndAt());
    }

    /**
     * M3: ensure stock key exists in Redis; if missing, reload from DB.
     */
    private void ensureStockLoaded(UUID ticketTypeId) {
        try {
            if (!redisService.stockKeyExists(ticketTypeId)) {
                TicketTypeInventoryEntity inv = inventoryRepository.findByTicketTypeId(ticketTypeId)
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.RESOURCE_NOT_FOUND, "Inventory not found", HttpStatus.NOT_FOUND));
                int available = inv.getTotalQty() - inv.getSoldQty() - inv.getReservedQty();
                redisService.setStock(ticketTypeId, available);
                log.debug(
                        "M3 seed-if-missing for reserve ticketTypeId={} available={}", ticketTypeId, available);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis unavailable during ensureStockLoaded for ticketTypeId={}", ticketTypeId, e);
            // Caller handles Redis-down path
        }
    }

    /**
     * Immutable holder for ticket type meta needed in reserve path.
     * perUserLimit: -1 means unlimited.
     */
    record TicketTypeMetaHolder(int perUserLimit, long price, Instant saleStartAt, Instant saleEndAt) {}
}
