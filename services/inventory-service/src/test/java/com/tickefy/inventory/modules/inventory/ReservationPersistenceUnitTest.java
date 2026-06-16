package com.tickefy.inventory.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.ReservationResponse;
import com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.service.ReservationPersistence;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests for ReservationPersistence.
 * No Spring context, no Docker, no Testcontainers.
 *
 * AC coverage:
 *  - toResponse: totalAmount = unitPrice * quantity (long arithmetic, no int overflow)
 *  - toResponse: large values do NOT overflow (unitPrice=2_000_000_000 * qty=5 > Integer.MAX)
 *  - writeReservationFallback: per-user limit exceeded -> 422 PER_USER_LIMIT_EXCEEDED
 *  - writeReservationFallback: within limit -> delegates to repo.save, returns valid response
 *  - writeReservationFallback: perUserLimit=-1 (unlimited) -> no 422 thrown
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationPersistenceUnitTest {

    @Mock
    private TicketReservationRepository reservationRepository;

    @Mock
    private TicketTypeInventoryRepository inventoryRepository;

    private ReservationPersistence persistence;

    private final UUID ticketTypeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        persistence = new ReservationPersistence(reservationRepository, inventoryRepository);
        // Inject @Value field (reservation-ttl) via ReflectionTestUtils — no Spring context
        ReflectionTestUtils.setField(persistence, "reservationTtl", Duration.ofMinutes(15));
    }

    // -----------------------------------------------------------------------
    // toResponse: totalAmount arithmetic
    // -----------------------------------------------------------------------

    @Test
    void toResponse_totalAmount_is_unitPrice_times_quantity_normal() {
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        TicketReservationEntity entity = TicketReservationEntity.builder()
                .id(reservationId)
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .orderId(orderId)
                .quantity(2)
                .status("RESERVED")
                .expiresAt(expiresAt)
                .build();

        long unitPrice = 3_000_000L; // 3 million VND
        ReservationResponse resp = persistence.toResponse(entity, unitPrice);

        assertThat(resp.reservationId()).isEqualTo(reservationId);
        assertThat(resp.ticketTypeId()).isEqualTo(ticketTypeId);
        assertThat(resp.quantity()).isEqualTo(2);
        assertThat(resp.unitPrice()).isEqualTo(3_000_000L);
        assertThat(resp.totalAmount()).isEqualTo(6_000_000L); // 3_000_000 * 2
        assertThat(resp.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void toResponse_totalAmount_does_not_overflow_with_large_values() {
        // 2_000_000_000 * 5 = 10_000_000_000 which is > Integer.MAX_VALUE (2_147_483_647)
        // If product were computed as int it would overflow; long arithmetic must be used.
        UUID reservationId = UUID.randomUUID();
        TicketReservationEntity entity = TicketReservationEntity.builder()
                .id(reservationId)
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .orderId(orderId)
                .quantity(5)
                .status("RESERVED")
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        long unitPrice = 2_000_000_000L; // 2 billion
        ReservationResponse resp = persistence.toResponse(entity, unitPrice);

        long expected = 10_000_000_000L;
        assertThat(resp.totalAmount())
                .as("totalAmount must NOT overflow — expected %d, got %d", expected, resp.totalAmount())
                .isEqualTo(expected);
        // Confirm it truly exceeds Integer.MAX_VALUE
        assertThat(resp.totalAmount()).isGreaterThan(Integer.MAX_VALUE);
    }

    @Test
    void toResponse_singleTicket_totalAmount_equals_unitPrice() {
        TicketReservationEntity entity = TicketReservationEntity.builder()
                .id(UUID.randomUUID())
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .orderId(orderId)
                .quantity(1)
                .status("RESERVED")
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        long unitPrice = 750_000L;
        ReservationResponse resp = persistence.toResponse(entity, unitPrice);

        assertThat(resp.totalAmount()).isEqualTo(750_000L);
        assertThat(resp.totalAmount()).isEqualTo(resp.unitPrice());
    }

    // -----------------------------------------------------------------------
    // writeReservationFallback: per-user limit logic
    // -----------------------------------------------------------------------

    @Test
    void writeReservationFallback_perUserLimit_exceeded_throws_422() {
        int perUserLimit = 4;
        // User already owns 3; requesting 2 more -> 3+2=5 > 4
        when(reservationRepository.sumActiveQuantity(userId, ticketTypeId)).thenReturn(3);

        assertThatThrownBy(() ->
                        persistence.writeReservationFallback(
                                ticketTypeId, userId, orderId, 2, perUserLimit, 500_000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.PER_USER_LIMIT_EXCEEDED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) api.getDetails();
                    assertThat(details).isNotNull();
                    assertThat(details.get("perUserLimit")).isEqualTo(perUserLimit);
                    assertThat(details.get("alreadyOwned")).isEqualTo(3);
                    assertThat(details.get("remaining")).isEqualTo(Math.max(0, perUserLimit - 3)); // 1
                });

        // inventory update must NOT be attempted
        verify(inventoryRepository, never())
                .incrementReservedConditional(any(), anyInt());
    }

    @Test
    void writeReservationFallback_withinLimit_savesReservation_returnsResponse() {
        int perUserLimit = 4;
        // User owns 0; requesting 2 -> within limit
        when(reservationRepository.sumActiveQuantity(userId, ticketTypeId)).thenReturn(0);
        // DB capacity guard: 1 row updated
        when(inventoryRepository.incrementReservedConditional(ticketTypeId, 2)).thenReturn(1);

        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        TicketReservationEntity savedEntity = TicketReservationEntity.builder()
                .id(reservationId)
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .orderId(orderId)
                .quantity(2)
                .status("RESERVED")
                .expiresAt(expiresAt)
                .build();
        when(reservationRepository.save(any())).thenReturn(savedEntity);

        ReservationResponse resp = persistence.writeReservationFallback(
                ticketTypeId, userId, orderId, 2, perUserLimit, 500_000L);

        assertThat(resp).isNotNull();
        assertThat(resp.reservationId()).isEqualTo(reservationId);
        assertThat(resp.quantity()).isEqualTo(2);
        assertThat(resp.unitPrice()).isEqualTo(500_000L);
        assertThat(resp.totalAmount()).isEqualTo(1_000_000L); // 500_000 * 2
    }

    @Test
    void writeReservationFallback_unlimitedUser_skipsLimitCheck_savesSuccessfully() {
        // perUserLimit = -1 means unlimited; sumActiveQuantity must NOT be queried
        when(inventoryRepository.incrementReservedConditional(ticketTypeId, 3)).thenReturn(1);

        UUID reservationId = UUID.randomUUID();
        TicketReservationEntity savedEntity = TicketReservationEntity.builder()
                .id(reservationId)
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .orderId(orderId)
                .quantity(3)
                .status("RESERVED")
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        when(reservationRepository.save(any())).thenReturn(savedEntity);

        ReservationResponse resp = persistence.writeReservationFallback(
                ticketTypeId, userId, orderId, 3, -1, 1_000_000L);

        assertThat(resp.totalAmount()).isEqualTo(3_000_000L);
        // sumActiveQuantity must NOT be called when limit is -1
        verify(reservationRepository, never())
                .sumActiveQuantity(any(), any());
    }

    @Test
    void writeReservationFallback_noStock_throws_ticketSoldOut() {
        // perUserLimit check passes (user owns 0, limit 4, requesting 1)
        when(reservationRepository.sumActiveQuantity(userId, ticketTypeId)).thenReturn(0);
        // DB guard rejects: 0 rows updated
        when(inventoryRepository.incrementReservedConditional(ticketTypeId, 1)).thenReturn(0);

        assertThatThrownBy(() ->
                        persistence.writeReservationFallback(
                                ticketTypeId, userId, orderId, 1, 4, 500_000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.TICKET_SOLD_OUT);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }
}
