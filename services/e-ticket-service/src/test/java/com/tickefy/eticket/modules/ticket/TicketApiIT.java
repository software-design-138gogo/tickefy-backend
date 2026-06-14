package com.tickefy.eticket.modules.ticket;

import static com.tickefy.eticket.support.JwtTestTokenFactory.bearer;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.rabbitmq.client.ConnectionFactory;
import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import com.tickefy.eticket.support.PostgresContainerITBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketApiIT extends PostgresContainerITBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory amqpConnectionFactory;

    @Autowired
    private TicketRepository ticketRepository;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        ticketRepository.deleteAll();
    }

    @Test
    void internalIssueAndCheckInFlow_shouldUseApiEnvelopeAndConcertIdContract() {
        String ticketId = issueTicket("order-1", "item-1")
                .then()
                .statusCode(201)
                .body("success", equalTo(true))
                .body("data.id", notNullValue())
                .body("data.concertId", equalTo("concert-1"))
                .body("data.qrToken", notNullValue())
                .extract()
                .path("data.id");
        assertThat(UUID.fromString(ticketId).version()).isEqualTo(4);

        String qrToken = given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .when()
                .get("/internal/tickets/snapshot?concertId=concert-1")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.concertId", equalTo("concert-1"))
                .extract()
                .path("data.tickets[0].qrToken");
        assertThat(UUID.fromString(qrToken).version()).isEqualTo(4);

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .when()
                .get("/internal/tickets/by-token/{token}", qrToken)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(ticketId))
                .body("data.concertId", equalTo("concert-1"));

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .when()
                .put("/internal/tickets/{id}/check-in", ticketId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("ACCEPTED"));

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .when()
                .put("/internal/tickets/{id}/check-in", ticketId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("DUPLICATE_REJECTED"));
    }

    @Test
    void internalIssue_whenSameOrderItemPostedTwice_returnsExistingTicketWithoutDuplicateRow() {
        Response first = issueTicket("order-1", "item-1")
                .then()
                .statusCode(201)
                .extract()
                .response();
        String firstId = first.path("data.id");
        String firstQrToken = first.path("data.qrToken");

        Response second = issueTicket("order-1", "item-1")
                .then()
                .statusCode(201)
                .body("data.id", equalTo(firstId))
                .body("data.qrToken", equalTo(firstQrToken))
                .extract()
                .response();

        assertThat(UUID.fromString(firstId).version()).isEqualTo(4);
        assertThat(UUID.fromString(firstQrToken).version()).isEqualTo(4);
        assertThat(second.<String>path("data.id")).isEqualTo(firstId);
        assertThat(second.<String>path("data.qrToken")).isEqualTo(firstQrToken);
    }

    @Test
    void internalCheckInByToken_shouldValidateConcertBeforeMutatingTicket() {
        String qrToken = issueTicket("order-token", "item-token")
                .then()
                .statusCode(201)
                .extract()
                .path("data.qrToken");

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .queryParam("concertId", "wrong-concert")
                .when()
                .put("/internal/tickets/by-token/{token}/check-in", qrToken)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("WRONG_EVENT"))
                .body("data.concertId", equalTo("concert-1"));

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .queryParam("concertId", "concert-1")
                .when()
                .put("/internal/tickets/by-token/{token}/check-in", qrToken)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("ACCEPTED"));

        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .queryParam("concertId", "concert-1")
                .when()
                .put("/internal/tickets/by-token/{token}/check-in", qrToken)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.result", equalTo("DUPLICATE_REJECTED"));
    }

    @Test
    void internalCheckInByToken_whenTokenInvalid_returnsInvalidQrEnvelope() {
        given()
                .header("Authorization", bearer("staff-1", "CHECKIN_STAFF"))
                .queryParam("concertId", "concert-1")
                .when()
                .put("/internal/tickets/by-token/{token}/check-in", "missing-token")
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("error.code", equalTo("INVALID_QR_TOKEN"));
    }

    @Test
    void customerTicketDetail_whenDifferentJwtSubject_returnsForbiddenEnvelope() {
        String ticketId = issueTicket("order-1", "item-1")
                .then()
                .statusCode(201)
                .extract()
                .path("data.id");

        given()
                .header("Authorization", bearer("user-2", "AUDIENCE"))
                .when()
                .get("/api/tickets/{id}", ticketId)
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("error.code", equalTo("TICKET_ACCESS_DENIED"));
    }

    @Test
    void authRules_shouldRejectMissingTokenAndAudienceInternalAccess() {
        given()
                .when()
                .get("/api/tickets")
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("error.code", equalTo("UNAUTHORIZED"));

        given()
                .header("Authorization", bearer("audience-1", "AUDIENCE"))
                .when()
                .get("/internal/tickets/snapshot?concertId=concert-1")
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("error.code", equalTo("FORBIDDEN"));
    }

    private io.restassured.response.Response issueTicket(String orderId, String orderItemId) {
        return given()
                .header("Authorization", bearer("admin-1", "ADMIN"))
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "orderId": "%s",
                          "orderItemId": "%s",
                          "userId": "user-1",
                          "concertId": "concert-1",
                          "ticketTypeId": "type-1",
                          "zoneId": "GA",
                          "ticketName": "General Admission"
                        }
                        """.formatted(orderId, orderItemId))
                .when()
                .post("/internal/tickets/issue");
    }
}
