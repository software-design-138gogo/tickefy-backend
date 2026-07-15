package com.tickefy.inventory.modules.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.service.ReservationLifecycleService;
import com.tickefy.inventory.modules.inventory.service.ReservationReaper;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for ReservationReaper.reap() — T3.8.
 *
 * NOTE: ReservationReaper.reap() is package-private (no modifier).
 * Test lives in a different package so we invoke via ReflectionTestUtils.invokeMethod().
 *
 * No Spring context, no Docker, no Testcontainers.
 * Grace and batchSize injected via ReflectionTestUtils (mirrors @Value injection).
 *
 * AC coverage:
 *  AC-reap-orphan         : stale RESERVED found → release() called with correct IDs once
 *  AC-reap-empty          : empty result → release() never called
 *  AC-reap-cutoff         : repo queried with status="RESERVED", before≈now-grace, PageRequest batchSize
 *  AC-reap-batch-skip-on-error : first release throws → batch continues, second release called
 *  AC-reap-release-idempotent  : lifecycle.release() no-ops when status=RELEASED or COMMITTED,
 *                                 and calls releaseReserved+compensateReserve when status=RESERVED
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationReaperUnitTest {

    @Mock
    private TicketReservationRepository reservationRepository;

    @Mock
    private ReservationLifecycleService lifecycleService;

    // For AC-reap-release-idempotent: lifecycle internals
    @Mock
    private com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository inventoryRepository;

    @Mock
    private com.tickefy.inventory.modules.inventory.service.InventoryRedisService redisService;

    private ReservationReaper reaper;

    private static final Duration TEST_GRACE = Duration.ofMinutes(5);
    private static final int TEST_BATCH = 50;

    @BeforeEach
    void setUp() {
        reaper = new ReservationReaper(reservationRepository, lifecycleService);
        ReflectionTestUtils.setField(reaper, "grace", TEST_GRACE);
        ReflectionTestUtils.setField(reaper, "batchSize", TEST_BATCH);
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private TicketReservationEntity staleReservation(UUID orderId, UUID ticketTypeId, int qty) {
        return TicketReservationEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .ticketTypeId(ticketTypeId)
                .userId(UUID.randomUUID())
                .quantity(qty)
                .status("RESERVED")
                .expiresAt(Instant.now().minus(TEST_GRACE).minusSeconds(60))
                .build();
    }

    // -------------------------------------------------------------------------
    // AC-reap-orphan
    // -------------------------------------------------------------------------

    /**
     * AC-reap-orphan: repo returns 1 stale RESERVED reservation →
     * lifecycleService.release() called exactly once with the correct orderId, ticketTypeId, qty.
     */
    @Test
    void reap_orphanFound_releaseCalledOnceWithCorrectIds() {
        UUID orderId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();
        int qty = 2;
        TicketReservationEntity stale = staleReservation(orderId, ticketTypeId, qty);

        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq("RESERVED"), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(stale));

        ReflectionTestUtils.invokeMethod(reaper, "reap");

        verify(lifecycleService, times(1)).release(orderId, ticketTypeId, qty);
    }

    // -------------------------------------------------------------------------
    // AC-reap-empty
    // -------------------------------------------------------------------------

    /**
     * AC-reap-empty: repo returns empty list → release() is never called.
     */
    @Test
    void reap_emptyResult_releaseNeverCalled() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq("RESERVED"), any(Instant.class), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());

        ReflectionTestUtils.invokeMethod(reaper, "reap");

        verify(lifecycleService, never()).release(any(), any(), any(int.class));
    }

    // -------------------------------------------------------------------------
    // AC-reap-cutoff
    // -------------------------------------------------------------------------

    /**
     * AC-reap-cutoff: repo is called with
     *   status="RESERVED",
     *   before ≈ now-grace (within a 2-second window to allow test execution time),
     *   PageRequest.of(0, batchSize).
     */
    @Test
    void reap_repoCalledWithCorrectStatusCutoffAndPageSize() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                any(String.class), any(Instant.class), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());

        Instant beforeCall = Instant.now().minus(TEST_GRACE);
        ReflectionTestUtils.invokeMethod(reaper, "reap");
        Instant afterCall = Instant.now().minus(TEST_GRACE);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);

        verify(reservationRepository).findByStatusAndExpiresAtBefore(
                statusCaptor.capture(), cutoffCaptor.capture(), pageCaptor.capture());

        assertThat(statusCaptor.getValue()).isEqualTo("RESERVED");

        Instant capturedCutoff = cutoffCaptor.getValue();
        // cutoff must be within [beforeCall-1s, afterCall+1s] to account for nanos drift
        assertThat(capturedCutoff)
                .as("cutoff should be ≈ now-grace; captured=%s, expected window [%s, %s]",
                        capturedCutoff, beforeCall.minusSeconds(1), afterCall.plusSeconds(1))
                .isAfterOrEqualTo(beforeCall.minusSeconds(1))
                .isBeforeOrEqualTo(afterCall.plusSeconds(1));

        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(TEST_BATCH);
        assertThat(pageCaptor.getValue().getPageNumber()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // AC-reap-batch-skip-on-error
    // -------------------------------------------------------------------------

    /**
     * AC-reap-batch-skip-on-error: 2 reservations in batch; release(#1) throws RuntimeException →
     * reap() does NOT propagate the exception; release(#2) is still called (batch continues).
     */
    @Test
    void reap_firstReleaseThrows_batchContinues_secondReleaseCalled() {
        UUID orderId1 = UUID.randomUUID();
        UUID ticketTypeId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();
        UUID ticketTypeId2 = UUID.randomUUID();

        TicketReservationEntity r1 = staleReservation(orderId1, ticketTypeId1, 1);
        TicketReservationEntity r2 = staleReservation(orderId2, ticketTypeId2, 3);

        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq("RESERVED"), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(r1, r2));

        // release for #1 blows up
        org.mockito.Mockito.doThrow(new RuntimeException("forced failure"))
                .when(lifecycleService).release(orderId1, ticketTypeId1, 1);

        // reap() must not throw even though release(#1) failed
        ReflectionTestUtils.invokeMethod(reaper, "reap");

        // #1 attempted
        verify(lifecycleService, times(1)).release(orderId1, ticketTypeId1, 1);
        // #2 also attempted despite #1 failure
        verify(lifecycleService, times(1)).release(orderId2, ticketTypeId2, 3);
    }

    // -------------------------------------------------------------------------
    // AC-reap-release-idempotent  (tests on ReservationLifecycleService directly)
    // -------------------------------------------------------------------------

    /**
     * AC-reap-release-idempotent / already-RELEASED:
     * release() when reservation is already RELEASED → no-op:
     *   - inventoryRepository.releaseReserved NOT called
     *   - redisService.compensateReserve NOT called
     */
    @Test
    void lifecycleRelease_alreadyReleased_isNoOp() {
        UUID orderId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();

        TicketReservationEntity alreadyReleased = TicketReservationEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .ticketTypeId(ticketTypeId)
                .userId(UUID.randomUUID())
                .quantity(1)
                .status("RELEASED")
                .expiresAt(Instant.now().minusSeconds(1000))
                .build();

        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.of(alreadyReleased));

        ReservationLifecycleService svc =
                new ReservationLifecycleService(reservationRepository, inventoryRepository, redisService);

        svc.release(orderId, ticketTypeId, 1);

        verify(inventoryRepository, never()).releaseReserved(any(), any(int.class));
        verify(redisService, never()).compensateReserve(any(), any(), any(int.class));
    }

    /**
     * AC-reap-release-idempotent / already-COMMITTED:
     * release() when reservation is COMMITTED → NOT releasing (no-op on inventory+redis).
     */
    @Test
    void lifecycleRelease_alreadyCommitted_notReleasing() {
        UUID orderId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();

        TicketReservationEntity committed = TicketReservationEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .ticketTypeId(ticketTypeId)
                .userId(UUID.randomUUID())
                .quantity(2)
                .status("COMMITTED")
                .expiresAt(Instant.now().minusSeconds(1000))
                .build();

        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.of(committed));

        ReservationLifecycleService svc =
                new ReservationLifecycleService(reservationRepository, inventoryRepository, redisService);

        svc.release(orderId, ticketTypeId, 2);

        verify(inventoryRepository, never()).releaseReserved(any(), any(int.class));
        verify(redisService, never()).compensateReserve(any(), any(), any(int.class));
    }

    /**
     * AC-reap-release-idempotent / RESERVED path:
     * release() when status=RESERVED → releaseReserved called, status set to RELEASED,
     * compensateReserve called with correct ticketTypeId, userId, qty.
     */
    @Test
    void lifecycleRelease_reserved_callsReleaseReservedAndCompensate() {
        UUID orderId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        int qty = 3;

        TicketReservationEntity reserved = TicketReservationEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .ticketTypeId(ticketTypeId)
                .userId(userId)
                .quantity(qty)
                .status("RESERVED")
                .expiresAt(Instant.now().minusSeconds(1000))
                .build();

        when(reservationRepository.findByOrderIdAndTicketTypeId(orderId, ticketTypeId))
                .thenReturn(Optional.of(reserved));
        // DB guard succeeds: 1 row updated
        when(inventoryRepository.releaseReserved(ticketTypeId, qty)).thenReturn(1);

        ReservationLifecycleService svc =
                new ReservationLifecycleService(reservationRepository, inventoryRepository, redisService);

        svc.release(orderId, ticketTypeId, qty);

        verify(inventoryRepository, times(1)).releaseReserved(ticketTypeId, qty);
        // status must have been set to RELEASED before save
        assertThat(reserved.getStatus()).isEqualTo("RELEASED");
        verify(reservationRepository, times(1)).save(reserved);
        verify(redisService, times(1)).compensateReserve(ticketTypeId, userId, qty);
    }
}
