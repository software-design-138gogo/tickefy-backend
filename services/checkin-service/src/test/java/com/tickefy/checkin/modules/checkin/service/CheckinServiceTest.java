package com.tickefy.checkin.modules.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.common.exception.ApiException;
import com.tickefy.checkin.common.exception.ErrorCode;
import com.tickefy.checkin.infrastructure.clients.ETicketClient;
import com.tickefy.checkin.modules.checkin.dto.ScanRequest;
import com.tickefy.checkin.modules.checkin.dto.ScanResponse;
import com.tickefy.checkin.modules.checkin.dto.SnapshotResponse;
import com.tickefy.checkin.modules.checkin.dto.SyncRequest;
import com.tickefy.checkin.modules.checkin.dto.SyncResponse;
import com.tickefy.checkin.modules.checkin.entity.CheckinEvent;
import com.tickefy.checkin.modules.checkin.repository.CheckinEventRepository;
import com.tickefy.checkin.modules.checkin.repository.SyncBatchRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public class CheckinServiceTest {

    @Autowired
    private CheckinService checkinService;

    @Autowired
    private CheckinEventRepository checkinEventRepository;

    @Autowired
    private SyncBatchRepository syncBatchRepository;

    @MockBean
    private ETicketClient eTicketClient;

    @BeforeEach
    void setUp() {
        checkinEventRepository.deleteAll();
        syncBatchRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        checkinEventRepository.deleteAll();
        syncBatchRepository.deleteAll();
    }

    @Test
    void scan_online_shouldReturnAccepted() {
        when(eTicketClient.checkInByToken("valid-token", "concert-1"))
                .thenReturn(new ETicketClient.CheckInTicketResult(
                        "ACCEPTED", "ticket-1", "concert-1", "GA", "General Admission", "user-1", "CHECKED_IN"));

        ScanRequest req = new ScanRequest("valid-token", "concert-1", "device-1", "gate-A");
        ScanResponse response = checkinService.scan(req, "staff-1");

        assertThat(response.result()).isEqualTo("ACCEPTED");
        assertThat(response.ticketId()).isEqualTo("ticket-1");

        List<CheckinEvent> events = checkinEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getResult()).isEqualTo("ACCEPTED");
    }

    @Test
    void scan_online_shouldLogDuplicateRejected() {
        when(eTicketClient.checkInByToken("already-used-token", "concert-1"))
                .thenReturn(new ETicketClient.CheckInTicketResult(
                        "DUPLICATE_REJECTED", "ticket-1", "concert-1", "GA", "General Admission", "user-1", "CHECKED_IN"));

        ScanRequest req = new ScanRequest("already-used-token", "concert-1", "device-1", "gate-A");
        ScanResponse response = checkinService.scan(req, "staff-1");

        assertThat(response.result()).isEqualTo("DUPLICATE_REJECTED");
        
        List<CheckinEvent> events = checkinEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getResult()).isEqualTo("DUPLICATE_REJECTED");
    }

    @Test
    void scan_online_shouldReturnInvalidQrWhenTicketNotFound() {
        when(eTicketClient.checkInByToken("invalid-token", "concert-1"))
                .thenReturn(new ETicketClient.CheckInTicketResult(
                        "INVALID_QR_TOKEN", null, "concert-1", null, null, null, null));

        ScanRequest req = new ScanRequest("invalid-token", "concert-1", "device-1", "gate-A");
        ScanResponse response = checkinService.scan(req, "staff-1");

        assertThat(response.result()).isEqualTo("INVALID_QR_TOKEN");
        verify(eTicketClient, never()).getTicketByToken(anyString());
        verify(eTicketClient, never()).checkIn(anyString());

        List<CheckinEvent> events = checkinEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getResult()).isEqualTo("INVALID_QR_TOKEN");
    }

    @Test
    void scan_online_shouldReturnWrongEventWhenTicketBelongsToDifferentConcert() {
        when(eTicketClient.checkInByToken("wrong-event-token", "concert-1"))
                .thenReturn(new ETicketClient.CheckInTicketResult(
                        "WRONG_EVENT", "ticket-1", "concert-2", "GA", "General Admission", "user-1", "ISSUED"));

        ScanRequest req = new ScanRequest("wrong-event-token", "concert-1", "device-1", "gate-A");
        ScanResponse response = checkinService.scan(req, "staff-1");

        assertThat(response.result()).isEqualTo("WRONG_EVENT");
        verify(eTicketClient, never()).checkIn(anyString());

        List<CheckinEvent> events = checkinEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getResult()).isEqualTo("WRONG_EVENT");
    }

    @Test
    void scan_whenTicketCancelledBetweenLookupAndCheckIn_returnsCancelledTicket() {
        when(eTicketClient.checkInByToken("race-cancel-token", "concert-1"))
                .thenReturn(new ETicketClient.CheckInTicketResult(
                        "TICKET_CANCELLED", "ticket-1", "concert-1", "GA", "General Admission", "user-1", "CANCELLED"));

        ScanRequest req = new ScanRequest("race-cancel-token", "concert-1", "device-1", "gate-A");
        ScanResponse response = checkinService.scan(req, "staff-1");

        assertThat(response.result()).isEqualTo("CANCELLED_REJECTED");
        assertThat(checkinEventRepository.findAll().get(0).getResult()).isEqualTo("CANCELLED_REJECTED");
    }

    @Test
    void scan_whenTicketRefundedBetweenLookupAndCheckIn_returnsRefundedTicket() {
        when(eTicketClient.checkInByToken("race-refund-token", "concert-1"))
                .thenReturn(new ETicketClient.CheckInTicketResult(
                        "TICKET_REFUNDED", "ticket-1", "concert-1", "GA", "General Admission", "user-1", "REFUNDED"));

        ScanRequest req = new ScanRequest("race-refund-token", "concert-1", "device-1", "gate-A");
        ScanResponse response = checkinService.scan(req, "staff-1");

        assertThat(response.result()).isEqualTo("REFUNDED_REJECTED");
        assertThat(checkinEventRepository.findAll().get(0).getResult()).isEqualTo("REFUNDED_REJECTED");
    }

    @Test
    void scan_online_shouldSurfaceETicketOutageInsteadOfInvalidQr() {
        when(eTicketClient.checkInByToken("valid-looking-token", "concert-1"))
                .thenThrow(new ApiException(
                        ErrorCode.ETICKET_SERVICE_UNAVAILABLE,
                        "e-ticket unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE));

        ScanRequest req = new ScanRequest("valid-looking-token", "concert-1", "device-1", "gate-A");

        assertThatThrownBy(() -> checkinService.scan(req, "staff-1"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ETICKET_SERVICE_UNAVAILABLE);
    }

    @Test
    void snapshot_shouldReturnTicketsFromETicketService() {
        when(eTicketClient.getSnapshot("concert-1")).thenReturn(List.of(
                new ETicketClient.SnapshotTicket(
                        "ticket-1",
                        "qr-t****en-1",
                        "hash-token-1",
                        "concert-1",
                        "GA",
                        "General Admission",
                        "user-1",
                        "ISSUED",
                        Instant.now())));

        SnapshotResponse response = checkinService.getSnapshot("concert-1", "gate-A");

        assertThat(response.snapshotId()).hasSize(36);
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.tickets()).hasSize(1);
        assertThat(response.tickets().get(0).ticketId()).isEqualTo("ticket-1");
        assertThat(response.tickets().get(0).qrTokenMasked()).isEqualTo("qr-t****en-1");
        assertThat(response.tickets().get(0).qrTokenHash()).isEqualTo("hash-token-1");
        assertThat(response.totalCount()).isEqualTo(1);
    }

    @Test
    void sync_offline_shouldHandleIdempotency() {
        ETicketClient.TicketInfo mockTicket =
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "ISSUED", "GA", "General Admission", "user-1");
        when(eTicketClient.getTicketByToken("sync-token")).thenReturn(Optional.of(mockTicket));
        when(eTicketClient.checkIn("ticket-1")).thenReturn("ACCEPTED");

        SyncRequest.SyncItem item = new SyncRequest.SyncItem("local-1", "sync-token", "OFFLINE_ACCEPTED", Instant.now());
        SyncRequest req = new SyncRequest("batch-1", "device-1", "concert-1", "gate-A", List.of(item));

        // First sync
        SyncResponse response1 = checkinService.sync(req, "staff-1");
        assertThat(response1.result()).isEqualTo("SYNC_BATCH_ACCEPTED");
        assertThat(response1.acceptedCount()).isEqualTo(1);
        
        // Clear mock invocations to ensure the second sync uses the cache
        clearInvocations(eTicketClient);

        // Second sync (idempotent)
        SyncResponse response2 = checkinService.sync(req, "staff-1");
        assertThat(response2.result()).isEqualTo("SYNC_BATCH_REPLAYED");
        assertThat(response2.replayDetected()).isTrue();
        assertThat(response2.acceptedCount()).isEqualTo(1);
        assertThat(response2.items().get(0).ticketId()).isEqualTo("ticket-1");
        assertThat(response2.items().get(0).offlineScanId()).isEqualTo("local-1");

        // eTicketClient should NOT be called again
        verify(eTicketClient, never()).getTicketByToken(anyString());
    }

    @Test
    void sync_offline_firstWinsConflictResolution() {
        // Device 1 and Device 2 both scanned the same ticket offline
        // Device 1 syncs first, gets ACCEPTED. Device 2 syncs next, gets DUPLICATE_REJECTED.

        ETicketClient.TicketInfo mockTicket1 =
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "ISSUED", "GA", "General Admission", "user-1");
        ETicketClient.TicketInfo mockTicket2 =
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "CHECKED_IN", "GA", "General Admission", "user-1");

        // Mock responses sequentially
        when(eTicketClient.getTicketByToken("conflict-token"))
                .thenReturn(Optional.of(mockTicket1))
                .thenReturn(Optional.of(mockTicket2));

        when(eTicketClient.checkIn("ticket-1"))
                .thenReturn("ACCEPTED");

        SyncRequest.SyncItem item1 = new SyncRequest.SyncItem("local-1", "conflict-token", "OFFLINE_ACCEPTED", Instant.now().minusSeconds(10));
        SyncRequest req1 = new SyncRequest("batch-1", "device-1", "concert-1", "gate-A", List.of(item1));

        SyncRequest.SyncItem item2 = new SyncRequest.SyncItem("local-2", "conflict-token", "OFFLINE_ACCEPTED", Instant.now().minusSeconds(5));
        SyncRequest req2 = new SyncRequest("batch-2", "device-2", "concert-1", "gate-B", List.of(item2));

        // Device 1 Syncs
        SyncResponse response1 = checkinService.sync(req1, "staff-1");
        assertThat(response1.acceptedCount()).isEqualTo(1);
        assertThat(response1.conflictCount()).isZero();

        // Device 2 Syncs
        SyncResponse response2 = checkinService.sync(req2, "staff-2");
        assertThat(response2.acceptedCount()).isZero();
        assertThat(response2.conflictCount()).isEqualTo(1);
        assertThat(response2.items().get(0).result()).isEqualTo("SYNC_DUPLICATE_REJECTED");
    }

    @Test
    void sync_whenTicketCancelledBetweenLookupAndCheckIn_returnsRejectedCancelledTicket() {
        ETicketClient.TicketInfo mockTicket =
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "ISSUED", "GA", "General Admission", "user-1");
        when(eTicketClient.getTicketByToken("race-cancel-token")).thenReturn(Optional.of(mockTicket));
        when(eTicketClient.checkIn("ticket-1")).thenReturn("TICKET_CANCELLED");

        SyncRequest.SyncItem item = new SyncRequest.SyncItem("local-1", "race-cancel-token", "OFFLINE_ACCEPTED", Instant.now());
        SyncRequest req = new SyncRequest("batch-cancel", "device-1", "concert-1", "gate-A", List.of(item));

        SyncResponse response = checkinService.sync(req, "staff-1");

        assertThat(response.acceptedCount()).isZero();
        assertThat(response.conflictCount()).isZero();
        assertThat(response.rejectedCount()).isEqualTo(1);
        assertThat(response.items().get(0).result()).isEqualTo("SYNC_CANCELLED_REJECTED");
    }

    @Test
    void sync_offline_whenTicketBelongsToDifferentConcert_returnsRejectedWrongEvent() {
        ETicketClient.TicketInfo mockTicket =
                new ETicketClient.TicketInfo("ticket-1", "concert-2", "ISSUED", "GA", "General Admission", "user-1");
        when(eTicketClient.getTicketByToken("wrong-event-token")).thenReturn(Optional.of(mockTicket));

        SyncRequest.SyncItem item = new SyncRequest.SyncItem("local-1", "wrong-event-token", "OFFLINE_ACCEPTED", Instant.now());
        SyncRequest req = new SyncRequest("batch-wrong-event", "device-1", "concert-1", "gate-A", List.of(item));

        SyncResponse response = checkinService.sync(req, "staff-1");

        assertThat(response.acceptedCount()).isZero();
        assertThat(response.conflictCount()).isZero();
        assertThat(response.rejectedCount()).isEqualTo(1);
        assertThat(response.items().get(0).result()).isEqualTo("SYNC_WRONG_EVENT");
        assertThat(checkinEventRepository.findAll().get(0).getResult()).isEqualTo("SYNC_WRONG_EVENT");
        verify(eTicketClient, never()).checkIn(anyString());
    }

    @Test
    void sync_offline_concurrentSameBatch_shouldReturnCachedResultWithoutDuplicateRows() throws Exception {
        ETicketClient.TicketInfo mockTicket =
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "ISSUED", "GA", "General Admission", "user-1");
        when(eTicketClient.getTicketByToken("sync-token")).thenReturn(Optional.of(mockTicket));
        when(eTicketClient.checkIn("ticket-1")).thenReturn("ACCEPTED");

        SyncRequest.SyncItem item = new SyncRequest.SyncItem("local-1", "sync-token", "OFFLINE_ACCEPTED", Instant.now());
        SyncRequest req = new SyncRequest("batch-concurrent", "device-1", "concert-1", "gate-A", List.of(item));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<SyncResponse> responses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    responses.add(checkinService.sync(req, "staff-1"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(responses).hasSize(2);
        assertThat(syncBatchRepository.findAll()).hasSize(1);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.syncBatchId()).isEqualTo("batch-concurrent");
            assertThat(response.acceptedCount()).isEqualTo(1);
            assertThat(response.items().get(0).offlineScanId()).isEqualTo("local-1");
        });
    }
}
