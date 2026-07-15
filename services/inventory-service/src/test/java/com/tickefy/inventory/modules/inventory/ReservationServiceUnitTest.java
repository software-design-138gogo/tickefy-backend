package com.tickefy.inventory.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.ReservationResponse;
import com.tickefy.inventory.modules.inventory.dto.ReserveRequest;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import com.tickefy.inventory.modules.inventory.service.InventoryRedisService;
import com.tickefy.inventory.modules.inventory.service.ReservationPersistence;
import com.tickefy.inventory.modules.inventory.service.ReservationService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * Pure unit tests for ReservationService reserve() logic.
 * No Spring context, no Docker, no Testcontainers.
 *
 * AC coverage:
 *  - Lua -2  -> 409 TICKET_SOLD_OUT
 *  - Lua -1  -> 422 PER_USER_LIMIT_EXCEEDED (details populated)
 *  - Lua  1  -> success, persistence called once, response propagated
 *  - sale-window: before start -> 403 SALE_WINDOW_CLOSED, no Lua call
 *  - sale-window: after end   -> 403 SALE_WINDOW_CLOSED, no Lua call
 *  - idempotent hit           -> returns existing reservation, no Lua call
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationServiceUnitTest {

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

    private ReservationService service;

    private final UUID ticketTypeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    // Valid sale window: started 1 hour ago, ends 1 hour from now
    private final Instant saleStart = Instant.now().minusSeconds(3_600);
    private final Instant saleEnd = Instant.now().plusSeconds(3_600);

    // Serialised epoch millis for Redis meta hash
    private final int perUserLimit = 2;
    private final long unitPrice = 500_000L;

    /** Build a valid Redis meta map with custom saleStart/saleEnd. */
    private Map<Object, Object> metaMap(Instant start, Instant end) {
        return Map.of(
                "perUserLimit", String.valueOf(perUserLimit),
                "price", String.valueOf(unitPrice),
                "saleStartAt", String.valueOf(start.toEpochMilli()),
                "saleEndAt", String.valueOf(end.toEpochMilli()));
    }

    /** Common happy-path stubs: no existing reservation, valid Redis meta, stock key exists,
     * ticket type present and NOT cancelled (concert-cancelled guard reads it from DB). */
    private void stubHappyPathBase() {
        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.empty());
        when(redisService.getMeta(ticketTypeId)).thenReturn(metaMap(saleStart, saleEnd));
        when(redisService.stockKeyExists(ticketTypeId)).thenReturn(true);
        when(ticketTypeRepository.findById(ticketTypeId)).thenReturn(Optional.of(
                com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity.builder()
                        .id(ticketTypeId)
                        .name("TT")
                        .concertCancelled(false)
                        .build()));
    }

    @BeforeEach
    void setUp() {
        service = new ReservationService(
                reservationRepository,
                inventoryRepository,
                ticketTypeRepository,
                redisService,
                persistence);
    }

    // -----------------------------------------------------------------------
    // AC: Lua returns -2 -> 409 TICKET_SOLD_OUT
    // -----------------------------------------------------------------------
    @Test
    void reserve_lua_negative2_throws_ticketSoldOut() {
        stubHappyPathBase();
        when(redisService.executeReserve(ticketTypeId, userId, 1, perUserLimit)).thenReturn(-2L);

        assertThatThrownBy(() -> service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.TICKET_SOLD_OUT);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        // persistence must NOT be called
        verify(persistence, never()).writeReservationToDb(any(), any(), any(), anyInt(), anyInt());
    }

    // -----------------------------------------------------------------------
    // AC: Lua returns -1 -> 422 PER_USER_LIMIT_EXCEEDED with details
    // -----------------------------------------------------------------------
    @Test
    void reserve_lua_negative1_throws_perUserLimitExceeded_with_details() {
        stubHappyPathBase();
        // User already owns 1 ticket; limit is 2; requesting 2 more would exceed
        int alreadyOwned = 1;
        when(redisService.executeReserve(ticketTypeId, userId, 2, perUserLimit)).thenReturn(-1L);
        when(redisService.getUserOwned(userId, ticketTypeId)).thenReturn(alreadyOwned);

        assertThatThrownBy(() -> service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 2)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.PER_USER_LIMIT_EXCEEDED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    // details map must contain all three fields
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) api.getDetails();
                    assertThat(details).isNotNull();
                    assertThat(details.get("perUserLimit")).isEqualTo(perUserLimit);
                    assertThat(details.get("alreadyOwned")).isEqualTo(alreadyOwned);
                    assertThat(details.get("remaining")).isEqualTo(Math.max(0, perUserLimit - alreadyOwned));
                });

        verify(persistence, never()).writeReservationToDb(any(), any(), any(), anyInt(), anyInt());
    }

    // -----------------------------------------------------------------------
    // AC: Lua returns 1 -> success, persistence called once, response propagated
    // -----------------------------------------------------------------------
    @Test
    void reserve_lua_positive1_success_persistenceCalled_once() {
        stubHappyPathBase();
        when(redisService.executeReserve(ticketTypeId, userId, 1, perUserLimit)).thenReturn(1L);

        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        ReservationResponse expected = new ReservationResponse(
                reservationId, ticketTypeId, 1, unitPrice, unitPrice /* totalAmount */, expiresAt, null);
        when(persistence.writeReservationToDb(ticketTypeId, userId, orderId, 1, unitPrice))
                .thenReturn(expected);

        ReservationResponse result = service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 1));

        assertThat(result).isNotNull();
        assertThat(result.reservationId()).isEqualTo(reservationId);
        assertThat(result.ticketTypeId()).isEqualTo(ticketTypeId);
        assertThat(result.quantity()).isEqualTo(1);
        assertThat(result.unitPrice()).isEqualTo(unitPrice);
        assertThat(result.totalAmount()).isEqualTo(unitPrice);
        assertThat(result.expiresAt()).isEqualTo(expiresAt);

        verify(persistence, times(1)).writeReservationToDb(ticketTypeId, userId, orderId, 1, unitPrice);
    }

    // -----------------------------------------------------------------------
    // Regression (PLAN-N1-FIX): reserve() enriches response with ticketTypeName from ticket_types.name
    // so order carries it loss-less into OrderPaid/TicketsIssued. Before fix this field did not exist.
    // -----------------------------------------------------------------------
    @Test
    void reserve_success_responseCarriesTicketTypeName() {
        stubHappyPathBase();
        when(redisService.executeReserve(ticketTypeId, userId, 1, perUserLimit)).thenReturn(1L);

        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        ReservationResponse base = new ReservationResponse(
                reservationId, ticketTypeId, 1, unitPrice, unitPrice, expiresAt, null);
        when(persistence.writeReservationToDb(ticketTypeId, userId, orderId, 1, unitPrice))
                .thenReturn(base);

        com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity tt =
                com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity.builder()
                        .id(ticketTypeId)
                        .name("N1GA")
                        .build();
        when(ticketTypeRepository.findById(ticketTypeId)).thenReturn(Optional.of(tt));

        ReservationResponse result = service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 1));

        assertThat(result.ticketTypeName()).isEqualTo("N1GA");
        // base fields preserved by the enrich wrapper
        assertThat(result.reservationId()).isEqualTo(reservationId);
        assertThat(result.totalAmount()).isEqualTo(unitPrice);
    }

    // -----------------------------------------------------------------------
    // AC: sale-window not started -> 403 SALE_WINDOW_CLOSED, no Lua call
    // -----------------------------------------------------------------------
    @Test
    void reserve_beforeSaleStart_throws_saleWindowClosed_noLua() {
        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.empty());
        // sale starts 1 hour from now (future)
        Instant futureStart = Instant.now().plusSeconds(3_600);
        Instant futureEnd = Instant.now().plusSeconds(7_200);
        when(redisService.getMeta(ticketTypeId)).thenReturn(metaMap(futureStart, futureEnd));

        assertThatThrownBy(() -> service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.SALE_WINDOW_CLOSED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        // Lua must NOT be invoked
        verify(redisService, never()).executeReserve(any(), any(), anyInt(), anyInt());
        verify(persistence, never()).writeReservationToDb(any(), any(), any(), anyInt(), anyInt());
    }

    // -----------------------------------------------------------------------
    // AC: sale-window ended -> 403 SALE_WINDOW_CLOSED, no Lua call
    // -----------------------------------------------------------------------
    @Test
    void reserve_afterSaleEnd_throws_saleWindowClosed_noLua() {
        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.empty());
        // sale ended 2 hours ago
        Instant pastStart = Instant.now().minusSeconds(7_200);
        Instant pastEnd = Instant.now().minusSeconds(3_600);
        when(redisService.getMeta(ticketTypeId)).thenReturn(metaMap(pastStart, pastEnd));

        assertThatThrownBy(() -> service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.SALE_WINDOW_CLOSED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verify(redisService, never()).executeReserve(any(), any(), anyInt(), anyInt());
        verify(persistence, never()).writeReservationToDb(any(), any(), any(), anyInt(), anyInt());
    }

    // -----------------------------------------------------------------------
    // AC: idempotent hit -> returns existing reservation, Lua never called
    // -----------------------------------------------------------------------
    @Test
    void reserve_idempotent_existingReservation_returnsExisting_noLua() {
        // Build a minimal entity stub using builder (Lombok entity)
        com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity existing =
                com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity.builder()
                        .id(UUID.randomUUID())
                        .ticketTypeId(ticketTypeId)
                        .userId(userId)
                        .orderId(orderId)
                        .quantity(1)
                        .status("RESERVED")
                        .expiresAt(Instant.now().plusSeconds(900))
                        .build();

        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.of(existing));
        // getMeta called by ensureMetaLoaded for price lookup in idempotent path
        when(redisService.getMeta(ticketTypeId)).thenReturn(metaMap(saleStart, saleEnd));

        ReservationResponse stubResponse = new ReservationResponse(
                existing.getId(), ticketTypeId, 1, unitPrice, unitPrice, existing.getExpiresAt(), null);
        when(persistence.toResponse(existing, unitPrice)).thenReturn(stubResponse);

        ReservationResponse result = service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 1));

        assertThat(result.reservationId()).isEqualTo(existing.getId());
        verify(redisService, never()).executeReserve(any(), any(), anyInt(), anyInt());
        verify(persistence, never()).writeReservationToDb(any(), any(), any(), anyInt(), anyInt());
        verify(persistence, times(1)).toResponse(existing, unitPrice);
    }

    // -----------------------------------------------------------------------
    // Concert-cancelled guard (CLAUDE §6.3): cancelled ticket type -> 409 CONCERT_CANCELLED,
    // Lua never invoked (fail-fast before stock check).
    // -----------------------------------------------------------------------
    @Test
    void reserve_concertCancelled_throws_conflict_noLua() {
        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.empty());
        when(redisService.getMeta(ticketTypeId)).thenReturn(metaMap(saleStart, saleEnd));
        when(ticketTypeRepository.findById(ticketTypeId)).thenReturn(Optional.of(
                com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity.builder()
                        .id(ticketTypeId)
                        .name("TT")
                        .concertCancelled(true)
                        .build()));

        assertThatThrownBy(() -> service.reserve(new ReserveRequest(userId, ticketTypeId, orderId, 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.CONCERT_CANCELLED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        // fail-fast: Lua + persistence must NOT run
        verify(redisService, never()).executeReserve(any(), any(), anyInt(), anyInt());
        verify(persistence, never()).writeReservationToDb(any(), any(), any(), anyInt(), anyInt());
    }
}
