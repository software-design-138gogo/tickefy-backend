package com.tickefy.checkin.modules.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.infrastructure.clients.ETicketClient;
import com.tickefy.checkin.modules.checkin.dto.ScanRequest;
import com.tickefy.checkin.modules.checkin.dto.ScanResponse;
import com.tickefy.checkin.modules.checkin.dto.SyncRequest;
import com.tickefy.checkin.modules.checkin.dto.SyncResponse;
import com.tickefy.checkin.modules.checkin.entity.CheckinEvent;
import com.tickefy.checkin.modules.checkin.repository.CheckinEventRepository;
import com.tickefy.checkin.modules.checkin.repository.SyncBatchRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        // Mock ticket info
        Map<String, Object> mockTicket = new HashMap<>();
        mockTicket.put("id", "ticket-1");
        mockTicket.put("eventId", "concert-1");
        mockTicket.put("status", "ISSUED");
        when(eTicketClient.getTicketByToken("valid-token")).thenReturn(mockTicket);
        when(eTicketClient.checkIn("ticket-1")).thenReturn("ACCEPTED");

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
        // Mock ticket already checked in
        Map<String, Object> mockTicket = new HashMap<>();
        mockTicket.put("id", "ticket-1");
        mockTicket.put("eventId", "concert-1");
        mockTicket.put("status", "CHECKED_IN");
        when(eTicketClient.getTicketByToken("already-used-token")).thenReturn(mockTicket);

        ScanRequest req = new ScanRequest("already-used-token", "concert-1", "device-1", "gate-A");
        ScanResponse response = checkinService.scan(req, "staff-1");

        assertThat(response.result()).isEqualTo("DUPLICATE_REJECTED");
        
        List<CheckinEvent> events = checkinEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getResult()).isEqualTo("DUPLICATE_REJECTED");
    }

    @Test
    void sync_offline_shouldHandleIdempotency() {
        Map<String, Object> mockTicket = new HashMap<>();
        mockTicket.put("id", "ticket-1");
        mockTicket.put("eventId", "concert-1");
        mockTicket.put("status", "ISSUED");
        when(eTicketClient.getTicketByToken("sync-token")).thenReturn(mockTicket);
        when(eTicketClient.checkIn("ticket-1")).thenReturn("ACCEPTED");

        SyncRequest.SyncItem item = new SyncRequest.SyncItem("sync-token", Instant.now());
        SyncRequest req = new SyncRequest("batch-1", "device-1", "concert-1", "gate-A", List.of(item));

        // First sync
        SyncResponse response1 = checkinService.sync(req, "staff-1");
        assertThat(response1.accepted()).hasSize(1);
        
        // Clear mock invocations to ensure the second sync uses the cache
        clearInvocations(eTicketClient);

        // Second sync (idempotent)
        SyncResponse response2 = checkinService.sync(req, "staff-1");
        assertThat(response2.accepted()).hasSize(1);
        assertThat(response2.accepted().get(0).ticketId()).isEqualTo("ticket-1");

        // eTicketClient should NOT be called again
        verify(eTicketClient, never()).getTicketByToken(anyString());
    }

    @Test
    void sync_offline_firstWinsConflictResolution() {
        // Device 1 and Device 2 both scanned the same ticket offline
        // Device 1 syncs first, gets ACCEPTED. Device 2 syncs next, gets DUPLICATE_REJECTED.

        Map<String, Object> mockTicket1 = new HashMap<>();
        mockTicket1.put("id", "ticket-1");
        mockTicket1.put("eventId", "concert-1");
        mockTicket1.put("status", "ISSUED");

        Map<String, Object> mockTicket2 = new HashMap<>();
        mockTicket2.put("id", "ticket-1");
        mockTicket2.put("eventId", "concert-1");
        mockTicket2.put("status", "CHECKED_IN"); // After device 1 syncs, state becomes CHECKED_IN

        // Mock responses sequentially
        when(eTicketClient.getTicketByToken("conflict-token"))
                .thenReturn(mockTicket1)
                .thenReturn(mockTicket2);

        when(eTicketClient.checkIn("ticket-1"))
                .thenReturn("ACCEPTED");

        SyncRequest.SyncItem item1 = new SyncRequest.SyncItem("conflict-token", Instant.now().minusSeconds(10));
        SyncRequest req1 = new SyncRequest("batch-1", "device-1", "concert-1", "gate-A", List.of(item1));

        SyncRequest.SyncItem item2 = new SyncRequest.SyncItem("conflict-token", Instant.now().minusSeconds(5));
        SyncRequest req2 = new SyncRequest("batch-2", "device-2", "concert-1", "gate-B", List.of(item2));

        // Device 1 Syncs
        SyncResponse response1 = checkinService.sync(req1, "staff-1");
        assertThat(response1.accepted()).hasSize(1);
        assertThat(response1.conflicts()).hasSize(0);

        // Device 2 Syncs
        SyncResponse response2 = checkinService.sync(req2, "staff-2");
        assertThat(response2.accepted()).hasSize(0);
        assertThat(response2.conflicts()).hasSize(1);
        assertThat(response2.conflicts().get(0).result()).isEqualTo("DUPLICATE_REJECTED");
    }
}
