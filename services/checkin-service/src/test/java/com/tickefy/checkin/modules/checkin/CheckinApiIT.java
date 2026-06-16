package com.tickefy.checkin.modules.checkin;

import static com.tickefy.checkin.support.JwtTestTokenFactory.bearer;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

import com.tickefy.checkin.infrastructure.clients.ETicketClient;
import com.tickefy.checkin.modules.checkin.repository.CheckinEventRepository;
import com.tickefy.checkin.modules.checkin.repository.SyncBatchRepository;
import com.tickefy.checkin.support.PostgresContainerITBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckinApiIT extends PostgresContainerITBase {

    @MockitoBean
    private ETicketClient eTicketClient;

    @Autowired
    private CheckinEventRepository checkinEventRepository;

    @Autowired
    private SyncBatchRepository syncBatchRepository;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        checkinEventRepository.deleteAll();
        syncBatchRepository.deleteAll();
    }

    @Test
    void scan_shouldReturnBusinessRejectionsAsHttp200Results() {
        when(eTicketClient.checkInByToken("valid-token", "concert-1")).thenReturn(
                new ETicketClient.CheckInTicketResult("ACCEPTED", "ticket-ok", "concert-1", "GA", "General Admission", "user-1", "CHECKED_IN"));
        when(eTicketClient.checkInByToken("invalid-token", "concert-1")).thenReturn(
                new ETicketClient.CheckInTicketResult("INVALID_QR_TOKEN", null, "concert-1", null, null, null, null));
        when(eTicketClient.checkInByToken("wrong-concert-token", "concert-1")).thenReturn(
                new ETicketClient.CheckInTicketResult("WRONG_EVENT", "ticket-1", "concert-2", "GA", "General Admission", "user-1", "ISSUED"));
        when(eTicketClient.checkInByToken("duplicate-token", "concert-1")).thenReturn(
                new ETicketClient.CheckInTicketResult("DUPLICATE_REJECTED", "ticket-2", "concert-1", "GA", "General Admission", "user-1", "CHECKED_IN"));
        when(eTicketClient.checkInByToken("cancelled-token", "concert-1")).thenReturn(
                new ETicketClient.CheckInTicketResult("TICKET_CANCELLED", "ticket-3", "concert-1", "GA", "General Admission", "user-1", "CANCELLED"));
        when(eTicketClient.checkInByToken("refunded-token", "concert-1")).thenReturn(
                new ETicketClient.CheckInTicketResult("TICKET_REFUNDED", "ticket-4", "concert-1", "GA", "General Admission", "user-1", "REFUNDED"));

        scan("valid-token", "concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("ACCEPTED"));

        scan("invalid-token", "concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("INVALID_QR_TOKEN"));

        scan("wrong-concert-token", "concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("WRONG_EVENT"));

        scan("duplicate-token", "concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("DUPLICATE_REJECTED"));

        scan("cancelled-token", "concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("CANCELLED_TICKET"));

        scan("refunded-token", "concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("REFUNDED_TICKET"));
    }

    @Test
    void snapshot_shouldReturnConcertIdContract() {
        when(eTicketClient.getSnapshot("concert-1")).thenReturn(List.of(
                new ETicketClient.SnapshotTicket(
                        "ticket-1",
                        "qr-token-1",
                        "concert-1",
                        "GA",
                        "General Admission",
                        "holder-1",
                        "ISSUED",
                        Instant.now())));

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .when()
                .get("/api/checkin/snapshot/{concertId}", "concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.concertId", equalTo("concert-1"))
                .body("data.tickets[0].concertId", equalTo("concert-1"));
    }

    @Test
    void sync_shouldNotReturnRawQrToken() {
        String rawToken = "raw-token-secret-1234";
        when(eTicketClient.getTicketByToken(rawToken)).thenReturn(Optional.of(
                new ETicketClient.TicketInfo("ticket-1", "concert-1", "ISSUED", "GA", "General Admission", "user-1")));
        when(eTicketClient.checkIn("ticket-1")).thenReturn("ACCEPTED");

        String response = given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "syncBatchId": "batch-api-mask",
                          "deviceId": "device-1",
                          "concertId": "concert-1",
                          "gate": "gate-A",
                          "items": [
                            {
                              "localId": "local-1",
                              "qrToken": "%s",
                              "localResult": "OFFLINE_ACCEPTED",
                              "scannedAt": "2026-06-11T19:30:00Z"
                            }
                          ]
                        }
                        """.formatted(rawToken))
                .when()
                .post("/api/checkin/sync")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.accepted[0].qrTokenMasked", equalTo("raw-****1234"))
                .extract()
                .asString();

        assertThat(response).doesNotContain(rawToken);
    }

    @Test
    void authAndValidationFailures_shouldReturnNon2xxErrorEnvelope() {
        given()
                .when()
                .post("/api/checkin/scan")
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("error.code", equalTo("UNAUTHORIZED"));

        given()
                .header("Authorization", bearer("audience-1", "AUDIENCE"))
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "qrToken": "qr-token",
                          "concertId": "concert-1",
                          "deviceId": "device-1",
                          "gate": "gate-A"
                        }
                        """)
                .when()
                .post("/api/checkin/scan")
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("error.code", equalTo("FORBIDDEN"));

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "qrToken": "qr-token",
                          "concertId": "concert-1",
                          "gate": "gate-A"
                        }
                        """)
                .when()
                .post("/api/checkin/scan")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    private io.restassured.response.Response scan(String qrToken, String concertId) {
        return given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "qrToken": "%s",
                          "concertId": "%s",
                          "deviceId": "device-1",
                          "gate": "gate-A"
                        }
                        """.formatted(qrToken, concertId))
                .when()
                .post("/api/checkin/scan");
    }
}
