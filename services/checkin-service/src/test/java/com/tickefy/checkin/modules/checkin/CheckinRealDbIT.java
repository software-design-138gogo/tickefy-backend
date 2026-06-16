package com.tickefy.checkin.modules.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.checkin.infrastructure.clients.ETicketClient;
import com.tickefy.checkin.modules.checkin.dto.SyncRequest;
import com.tickefy.checkin.modules.checkin.repository.CheckinEventRepository;
import com.tickefy.checkin.modules.checkin.repository.SyncBatchRepository;
import com.tickefy.checkin.modules.checkin.service.CheckinService;
import com.tickefy.checkin.support.PostgresContainerITBase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class CheckinRealDbIT extends PostgresContainerITBase {

    @MockitoBean
    private ETicketClient eTicketClient;

    @Autowired
    private CheckinService checkinService;

    @Autowired
    private CheckinEventRepository checkinEventRepository;

    @Autowired
    private SyncBatchRepository syncBatchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        checkinEventRepository.deleteAll();
        syncBatchRepository.deleteAll();
    }

    @Test
    void flywayMigration_shouldCreateCheckinTablesWithConcertIdContract() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'checkin_service'
                """, String.class);
        Integer eventColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'checkin_service'
                  AND column_name = 'event_id'
                """, Integer.class);

        assertThat(tables).contains("checkin_events", "sync_batches", "checkin_snapshots", "conflicts");
        assertThat(eventColumns).isZero();
    }

    @Test
    void syncBatchIdConstraint_shouldRejectDuplicateBatchIds() {
        insertSyncBatch("batch-constraint");

        assertThatThrownBy(() -> insertSyncBatch("batch-constraint"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sync_shouldPersistOnlyMaskedQrTokenInResponseAuditAndCachedPayload() {
        String rawToken = "raw-token-secret-1234";
        when(eTicketClient.getTicketByToken(rawToken)).thenReturn(Optional.of(
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "ISSUED", "GA", "General Admission", "user-1")));
        when(eTicketClient.checkIn("ticket-1")).thenReturn("ACCEPTED");

        SyncRequest request = new SyncRequest(
                "batch-mask",
                "device-1",
                "concert-1",
                "gate-A",
                List.of(new SyncRequest.SyncItem("local-1", rawToken, "OFFLINE_ACCEPTED", Instant.now())));

        var response = checkinService.sync(request, "staff-1");

        assertThat(response.accepted()).hasSize(1);
        assertThat(response.accepted().get(0).qrTokenMasked()).isEqualTo("raw-****1234");
        assertThat(response.accepted().get(0).qrTokenMasked()).isNotEqualTo(rawToken);
        assertThat(checkinEventRepository.findAll().get(0).getQrTokenMasked()).isEqualTo("raw-****1234");
        assertThat(syncBatchRepository.findBySyncBatchId("batch-mask").orElseThrow().getResultPayload())
                .contains("qrTokenMasked")
                .contains("raw-****1234")
                .doesNotContain(rawToken);
    }

    @Test
    void sync_whenSameBatchSubmittedAgain_returnsCachedDbResultWithoutCallingETicketAgain() {
        String rawToken = "sync-token-secret-1234";
        when(eTicketClient.getTicketByToken(rawToken)).thenReturn(Optional.of(
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "ISSUED", "GA", "General Admission", "user-1")));
        when(eTicketClient.checkIn("ticket-1")).thenReturn("ACCEPTED");

        SyncRequest request = new SyncRequest(
                "batch-idempotent",
                "device-1",
                "concert-1",
                "gate-A",
                List.of(new SyncRequest.SyncItem("local-1", rawToken, "OFFLINE_ACCEPTED", Instant.now())));

        checkinService.sync(request, "staff-1");
        clearInvocations(eTicketClient);

        var replay = checkinService.sync(request, "staff-1");

        assertThat(replay.accepted()).hasSize(1);
        assertThat(syncBatchRepository.findAll()).hasSize(1);
        verify(eTicketClient, never()).getTicketByToken(rawToken);
    }

    private void insertSyncBatch(String syncBatchId) {
        jdbcTemplate.update("""
                INSERT INTO checkin_service.sync_batches
                    (id, sync_batch_id, device_id, concert_id, staff_id, item_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), syncBatchId, "device-1", "concert-1", "staff-1", 1);
    }
}
