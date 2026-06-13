package com.tickefy.inventory.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.ReservationResponse;
import com.tickefy.inventory.modules.inventory.dto.ReserveRequest;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import com.tickefy.inventory.modules.inventory.service.InventoryRedisService;
import com.tickefy.inventory.modules.inventory.service.ReservationPersistence;
import com.tickefy.inventory.modules.inventory.service.ReservationService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for ReservationService fallback logic when Redis is down.
 * No Docker needed — mocks InventoryRedisService to simulate Redis failure.
 *
 * AC10 (ticket-purchase): Redis down → fallback DB conditional UPDATE, no oversell.
 *
 * After backend-worker extracted ReservationPersistence as a separate Spring bean (to fix
 * self-invocation / @Transactional no-op), this test mocks ReservationPersistence directly
 * instead of the individual repository calls that now live inside that bean.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationServiceFallbackTest {

    @Mock
    private TicketReservationRepository reservationRepository;

    @Mock
    private TicketTypeInventoryRepository inventoryRepository;

    @Mock
    private TicketTypeRepository ticketTypeRepository;

    @Mock
    private InventoryRedisService redisService;

    @Mock
    private ReservationPersistence persistence;

    private ReservationService reservationService;

    private final UUID ticketTypeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final Instant saleStart = Instant.now().minusSeconds(3600);
    private final Instant saleEnd = Instant.now().plusSeconds(3600);

    @BeforeEach
    void setUp() {
        // ReservationService now requires 5 args: repos + redisService + persistence bean
        reservationService = new ReservationService(
                reservationRepository, inventoryRepository, ticketTypeRepository, redisService, persistence);
        // reservationTtl @Value field is now in ReservationPersistence, not ReservationService.
        // Tests mock persistence.writeReservationFallback() directly so no field injection needed.
    }

    /**
     * AC10: When Redis throws RedisConnectionFailureException on EVALSHA,
     * the service should fall back to persistence.writeReservationFallback() and succeed if stock available.
     */
    @Test
    void reserve_redisDown_fallbackDb_succeeds() {
        // Arrange
        ReserveRequest req = new ReserveRequest(userId, ticketTypeId, orderId, 1);

        // idempotent check — no existing reservation
        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.empty());

        // Redis meta is missing (returns null) → triggers DB rebuild (M3)
        when(redisService.getMeta(ticketTypeId)).thenReturn(null);

        // DB ticket type for meta rebuild
        TicketTypeEntity tt = TicketTypeEntity.builder()
                .concertId(UUID.randomUUID())
                .name("TEST")
                .price(1000)
                .perUserLimit(null)
                .saleStartAt(saleStart)
                .saleEndAt(saleEnd)
                .build();
        ReflectionTestUtils.setField(tt, "id", ticketTypeId);
        when(ticketTypeRepository.findById(ticketTypeId)).thenReturn(Optional.of(tt));

        // Stock key check — throw to indicate Redis down (triggers catch → redisAvailable=false path)
        when(redisService.stockKeyExists(ticketTypeId))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        // EVALSHA also throws (Redis down) — but stockKeyExists already swallowed, so
        // ensureStockLoaded catches it; executeReserve is not reached. Still stub for safety.
        when(redisService.executeReserve(any(), any(), anyInt(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        // persistence.writeReservationFallback() returns a valid response (DB has stock)
        UUID reservationId = UUID.randomUUID();
        ReservationResponse expected = new ReservationResponse(
                reservationId, ticketTypeId, 1, Instant.now().plusSeconds(900));
        // perUserLimit=null → -1 (unlimited), so writeReservationFallback called with -1
        when(persistence.writeReservationFallback(ticketTypeId, userId, orderId, 1, -1))
                .thenReturn(expected);

        // Act
        ReservationResponse response = reservationService.reserve(req);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.quantity()).isEqualTo(1);
        assertThat(response.reservationId()).isEqualTo(reservationId);
        // Verify fallback path delegated to persistence (not direct repo access)
        verify(persistence).writeReservationFallback(ticketTypeId, userId, orderId, 1, -1);
        // Verify compensateReserve was NOT called (Redis path never succeeded)
        verify(redisService, never()).compensateReserve(any(), any(), anyInt());
        // Verify sumActiveQuantity NOT called directly by ReservationService (lives in persistence now)
        verify(reservationRepository, never()).sumActiveQuantity(any(), any());
    }

    /**
     * AC10: When Redis is down AND DB has no stock,
     * persistence.writeReservationFallback() throws TICKET_SOLD_OUT — service propagates it.
     */
    @Test
    void reserve_redisDown_dbNoStock_soldOut() {
        ReserveRequest req = new ReserveRequest(userId, ticketTypeId, orderId, 1);

        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.empty());
        when(redisService.getMeta(ticketTypeId)).thenReturn(null);

        TicketTypeEntity tt = TicketTypeEntity.builder()
                .concertId(UUID.randomUUID())
                .name("TEST")
                .price(1000)
                .perUserLimit(null)
                .saleStartAt(saleStart)
                .saleEndAt(saleEnd)
                .build();
        ReflectionTestUtils.setField(tt, "id", ticketTypeId);
        when(ticketTypeRepository.findById(ticketTypeId)).thenReturn(Optional.of(tt));

        when(redisService.stockKeyExists(ticketTypeId))
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        when(redisService.executeReserve(any(), any(), anyInt(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        // DB fallback: no available stock → persistence throws TICKET_SOLD_OUT
        when(persistence.writeReservationFallback(ticketTypeId, userId, orderId, 1, -1))
                .thenThrow(new ApiException(ErrorCode.TICKET_SOLD_OUT, "Tickets are sold out", HttpStatus.CONFLICT));

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                () -> reservationService.reserve(req));
        assertThat(ex.getErrorCode().name()).isEqualTo("TICKET_SOLD_OUT");
    }

    /**
     * AC10: Redis down + per-user limit exceeded in DB fallback path.
     * persistence.writeReservationFallback() throws PER_USER_LIMIT_EXCEEDED.
     */
    @Test
    void reserve_redisDown_perUserLimitExceeded_db() {
        int perUserLimit = 4;
        ReserveRequest req = new ReserveRequest(userId, ticketTypeId, orderId, 3);

        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.empty());
        when(redisService.getMeta(ticketTypeId)).thenReturn(null);

        TicketTypeEntity tt = TicketTypeEntity.builder()
                .concertId(UUID.randomUUID())
                .name("TEST")
                .price(1000)
                .perUserLimit(perUserLimit)
                .saleStartAt(saleStart)
                .saleEndAt(saleEnd)
                .build();
        ReflectionTestUtils.setField(tt, "id", ticketTypeId);
        when(ticketTypeRepository.findById(ticketTypeId)).thenReturn(Optional.of(tt));

        when(redisService.stockKeyExists(ticketTypeId))
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        when(redisService.executeReserve(any(), any(), anyInt(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        // persistence throws PER_USER_LIMIT_EXCEEDED (user already owns 3, requesting 3 more = exceeds limit=4)
        when(persistence.writeReservationFallback(ticketTypeId, userId, orderId, 3, perUserLimit))
                .thenThrow(new ApiException(
                        ErrorCode.PER_USER_LIMIT_EXCEEDED,
                        "Per-user limit exceeded",
                        HttpStatus.UNPROCESSABLE_ENTITY));

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                () -> reservationService.reserve(req));

        assertThat(ex.getErrorCode().name()).isEqualTo("PER_USER_LIMIT_EXCEEDED");
    }
}
