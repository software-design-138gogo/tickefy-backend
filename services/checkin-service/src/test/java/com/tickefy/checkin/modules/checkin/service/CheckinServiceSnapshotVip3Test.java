package com.tickefy.checkin.modules.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tickefy.checkin.common.exception.ApiException;
import com.tickefy.checkin.common.exception.ErrorCode;
import com.tickefy.checkin.infrastructure.clients.ETicketClient;
import com.tickefy.checkin.modules.checkin.dto.SnapshotResponse;
import com.tickefy.checkin.modules.vip.dto.VipGuestSnapshotDto;
import com.tickefy.checkin.modules.vip.service.VipProjectionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * vip-3 snapshot seam tests — surefire, H2 in-memory (profile test), no Testcontainers.
 *
 * AC map:
 * AC-VIP3-1  getSnapshot returns vipGuests list (size 2, fields correct) + tickets intact.
 * AC-VIP3-2  getSnapshot graceful when VipProjectionService throws ApiException 503 →
 *            no exception thrown, vipGuests=[], tickets intact.
 * AC-VIP3-3  getSnapshot graceful when VipProjectionService throws RuntimeException →
 *            no exception thrown, vipGuests=[], tickets intact.
 * AC-VIP3-4  SnapshotResponse shape: 6 old fields (concertId/gate/generatedAt/expiresAt/
 *            totalCount/tickets) + new vipGuests field (field #7) all accessible.
 * AC-VIP3-5  SnapshotTicket shape: 8 fields (ticketId/qrTokenMasked/qrTokenHash/concertId/
 *            zoneId/zoneName/holderName/status) all intact.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("unit")
class CheckinServiceSnapshotVip3Test {

    @Autowired
    private CheckinService checkinService;

    @MockitoBean
    private ETicketClient eTicketClient;

    @MockitoBean
    private VipProjectionService vipProjectionService;

    private static final String CONCERT_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String GATE = "gate-VIP";

    private static final ETicketClient.SnapshotTicket MOCK_TICKET =
            new ETicketClient.SnapshotTicket(
                    "ticket-vip-1",
                    "qr-t****en-v",
                    "hash-vip-token-1",
                    CONCERT_ID,
                    "VIP-ZONE",
                    "VIP Section",
                    "John Doe",
                    "ISSUED",
                    Instant.now());

    @BeforeEach
    void setUp() {
        when(eTicketClient.getSnapshot(CONCERT_ID)).thenReturn(List.of(MOCK_TICKET));
    }

    // -------------------------------------------------------------------------
    // AC-VIP3-1  getSnapshot returns vipGuests list (size 2, fields correct)
    // -------------------------------------------------------------------------

    @Test
    void getSnapshot_withVipGuestsReturned_shouldIncludeVipGuestsInResponse() {
        List<VipGuestSnapshotDto> vips = List.of(
                new VipGuestSnapshotDto("alice@example.com", "Alice Wonder", "Gold VIP"),
                new VipGuestSnapshotDto("bob@example.com", "Bob Builder", "Silver VIP"));
        when(vipProjectionService.getVipGuestsForSnapshot(any())).thenReturn(vips);

        SnapshotResponse response = checkinService.getSnapshot(CONCERT_ID, GATE);

        // vipGuests populated
        assertThat(response.vipGuests())
                .as("AC-VIP3-1: vipGuests must have 2 entries")
                .hasSize(2);
        assertThat(response.vipGuests().get(0).email())
                .as("AC-VIP3-1: first VIP email must match")
                .isEqualTo("alice@example.com");
        assertThat(response.vipGuests().get(0).fullName())
                .as("AC-VIP3-1: first VIP fullName must match")
                .isEqualTo("Alice Wonder");
        assertThat(response.vipGuests().get(0).ticketTypeName())
                .as("AC-VIP3-1: first VIP ticketTypeName must match")
                .isEqualTo("Gold VIP");
        assertThat(response.vipGuests().get(1).email())
                .as("AC-VIP3-1: second VIP email must match")
                .isEqualTo("bob@example.com");

        // tickets still intact
        assertThat(response.tickets())
                .as("AC-VIP3-1: tickets must be intact (size 1)")
                .hasSize(1);
        assertThat(response.tickets().get(0).ticketId())
                .as("AC-VIP3-1: ticket ticketId must match")
                .isEqualTo("ticket-vip-1");
        assertThat(response.totalCount())
                .as("AC-VIP3-1: totalCount must equal tickets.size()")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC-VIP3-2  graceful on ApiException 503 → vipGuests=[], tickets intact
    // -------------------------------------------------------------------------

    @Test
    void getSnapshot_whenVipServiceThrowsApiException503_shouldNotThrowAndReturnEmptyVipGuests() {
        when(vipProjectionService.getVipGuestsForSnapshot(any()))
                .thenThrow(new ApiException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "VIP source unavailable and no cached data",
                        HttpStatus.SERVICE_UNAVAILABLE));

        // Must NOT throw
        assertThatNoException().isThrownBy(() -> {
            SnapshotResponse response = checkinService.getSnapshot(CONCERT_ID, GATE);

            assertThat(response.vipGuests())
                    .as("AC-VIP3-2: vipGuests must be empty list on 503, not null")
                    .isNotNull()
                    .isEmpty();
            assertThat(response.tickets())
                    .as("AC-VIP3-2: tickets must be intact despite VIP failure")
                    .hasSize(1);
            assertThat(response.tickets().get(0).ticketId())
                    .isEqualTo("ticket-vip-1");
        });
    }

    // -------------------------------------------------------------------------
    // AC-VIP3-3  graceful on RuntimeException → vipGuests=[], tickets intact
    // -------------------------------------------------------------------------

    @Test
    void getSnapshot_whenVipServiceThrowsRuntimeException_shouldNotThrowAndReturnEmptyVipGuests() {
        when(vipProjectionService.getVipGuestsForSnapshot(any()))
                .thenThrow(new RuntimeException("unexpected db failure"));

        assertThatNoException().isThrownBy(() -> {
            SnapshotResponse response = checkinService.getSnapshot(CONCERT_ID, GATE);

            assertThat(response.vipGuests())
                    .as("AC-VIP3-3: vipGuests must be empty list on RuntimeException, not null")
                    .isNotNull()
                    .isEmpty();
            assertThat(response.tickets())
                    .as("AC-VIP3-3: tickets must be intact despite VIP RuntimeException")
                    .hasSize(1);
        });
    }

    // -------------------------------------------------------------------------
    // AC-VIP3-4  SnapshotResponse shape: 6 old fields + vipGuests (field #7)
    // -------------------------------------------------------------------------

    @Test
    void snapshotResponse_shapeShouldHaveAllSevenFields() {
        when(vipProjectionService.getVipGuestsForSnapshot(any())).thenReturn(List.of());

        SnapshotResponse response = checkinService.getSnapshot(CONCERT_ID, GATE);

        // field 1: concertId
        assertThat(response.concertId())
                .as("AC-VIP3-4: field concertId must equal input")
                .isEqualTo(CONCERT_ID);
        // field 2: gate
        assertThat(response.gate())
                .as("AC-VIP3-4: field gate must equal input")
                .isEqualTo(GATE);
        // field 3: generatedAt
        assertThat(response.generatedAt())
                .as("AC-VIP3-4: field generatedAt must not be null")
                .isNotNull();
        // field 4: expiresAt
        assertThat(response.expiresAt())
                .as("AC-VIP3-4: field expiresAt must be after generatedAt")
                .isAfter(response.generatedAt());
        // field 5: totalCount
        assertThat(response.totalCount())
                .as("AC-VIP3-4: field totalCount must equal tickets list size")
                .isEqualTo(response.tickets().size());
        // field 6: tickets
        assertThat(response.tickets())
                .as("AC-VIP3-4: field tickets must not be null")
                .isNotNull();
        // field 7: vipGuests (NEW)
        assertThat(response.vipGuests())
                .as("AC-VIP3-4: field vipGuests (NEW) must not be null")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC-VIP3-5  SnapshotTicket shape: 8 fields all intact
    // -------------------------------------------------------------------------

    @Test
    void snapshotTicket_shapeShouldHaveAllEightFields() {
        when(vipProjectionService.getVipGuestsForSnapshot(any())).thenReturn(List.of());

        SnapshotResponse response = checkinService.getSnapshot(CONCERT_ID, GATE);

        assertThat(response.tickets()).hasSize(1);
        SnapshotResponse.SnapshotTicket t = response.tickets().get(0);

        assertThat(t.ticketId())
                .as("AC-VIP3-5: SnapshotTicket.ticketId")
                .isEqualTo("ticket-vip-1");
        assertThat(t.qrTokenMasked())
                .as("AC-VIP3-5: SnapshotTicket.qrTokenMasked")
                .isEqualTo("qr-t****en-v");
        assertThat(t.qrTokenHash())
                .as("AC-VIP3-5: SnapshotTicket.qrTokenHash")
                .isEqualTo("hash-vip-token-1");
        assertThat(t.concertId())
                .as("AC-VIP3-5: SnapshotTicket.concertId")
                .isEqualTo(CONCERT_ID);
        assertThat(t.zoneId())
                .as("AC-VIP3-5: SnapshotTicket.zoneId")
                .isEqualTo("VIP-ZONE");
        assertThat(t.zoneName())
                .as("AC-VIP3-5: SnapshotTicket.zoneName")
                .isEqualTo("VIP Section");
        assertThat(t.holderName())
                .as("AC-VIP3-5: SnapshotTicket.holderName")
                .isEqualTo("John Doe");
        assertThat(t.status())
                .as("AC-VIP3-5: SnapshotTicket.status")
                .isEqualTo("ISSUED");
    }
}
