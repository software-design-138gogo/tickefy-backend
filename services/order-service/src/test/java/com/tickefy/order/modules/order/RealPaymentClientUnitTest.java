package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.order.modules.order.client.CreatePaymentCommand;
import com.tickefy.order.modules.order.client.PaymentResult;
import com.tickefy.order.modules.order.client.PaymentRefundException;
import com.tickefy.order.modules.order.client.PaymentUnavailableException;
import com.tickefy.order.modules.order.client.RealPaymentClient;
import com.tickefy.order.modules.order.client.RefundRequest;
import com.tickefy.order.modules.order.client.RefundResponse;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for RealPaymentClient.
 *
 * Strategy: MockRestServiceServer (spring-test, already on classpath via spring-boot-starter-test).
 * We construct RealPaymentClient with a dummy baseUrl, then use reflection to replace its
 * private `restClient` field with a MockRestServiceServer-backed RestClient.
 *
 * This mirrors the InventoryClientUnitTest precedent: reflection to bypass constructor
 * coupling to external URLs/infrastructure. No Docker, no Testcontainers, no WireMock server.
 *
 * Note: MockRestServiceServer uses SimpleClientHttpRequestFactory (HTTP/1.1) so there are
 * no HTTP/2 negotiation issues that arise with Java HttpClient + WireMock.
 */
@Tag("unit")
class RealPaymentClientUnitTest {

    private static final String DUMMY_BASE_URL = "http://payment-service-dummy";
    private static final String PAYMENT_PATH = "/internal/payments";
    private static final String REFUND_PATH = "/internal/payments/refund";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RealPaymentClient client;
    private MockRestServiceServer mockServer;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long AMOUNT = 150000L;
    private static final String BEARER_TOKEN = "test-bearer-token-xyz";

    @BeforeEach
    void setUp() throws Exception {
        // 1. Construct RealPaymentClient with dummy base URL
        client = new RealPaymentClient(DUMMY_BASE_URL, PAYMENT_PATH, MAPPER);

        // 2. Build a MockRestServiceServer-backed RestClient
        //    We wire it to the same base URL so the MockRestServiceServer can match requests
        RestClient.Builder builder = RestClient.builder().baseUrl(DUMMY_BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient mockedRestClient = builder.build();

        // 3. Inject the mocked RestClient into the private field via reflection
        //    (mirrors InventoryClientUnitTest reflection approach)
        Field restClientField = RealPaymentClient.class.getDeclaredField("restClient");
        restClientField.setAccessible(true);
        restClientField.set(client, mockedRestClient);

        Field refundRestClientField = RealPaymentClient.class.getDeclaredField("refundRestClient");
        refundRestClientField.setAccessible(true);
        refundRestClientField.set(client, mockedRestClient);
    }

    // -----------------------------------------------------------------------
    // AC-real-create: 201 → PaymentResult(transactionId="P1", paymentUrl set, status="PENDING")
    // -----------------------------------------------------------------------

    @Test
    void acRealCreate_201_returnsPaymentResultWithPaymentIdAndPending() throws Exception {
        String responseBody = """
                {
                  "success": true,
                  "data": {
                    "paymentId": "P1",
                    "paymentUrl": "http://pay.example.com/tx/P1"
                  },
                  "error": null,
                  "requestId": "req-001"
                }
                """;

        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        PaymentResult result = client.createTransaction(cmd, BEARER_TOKEN);

        mockServer.verify();
        assertThat(result.transactionId()).isEqualTo("P1");
        assertThat(result.paymentUrl()).isEqualTo("http://pay.example.com/tx/P1");
        assertThat(result.status()).isEqualTo("PENDING");
    }

    // -----------------------------------------------------------------------
    // AC-auth-forward: Authorization: Bearer <token> forwarded exactly
    // -----------------------------------------------------------------------

    @Test
    void acAuthForward_bearerTokenForwardedInAuthorizationHeader() throws Exception {
        String responseBody = """
                {
                  "success": true,
                  "data": {"paymentId": "P2", "paymentUrl": "http://pay.example.com/tx/P2"},
                  "error": null,
                  "requestId": "req-002"
                }
                """;

        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        client.createTransaction(cmd, BEARER_TOKEN);

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-real-create (body): request body contains orderId, userId, amount, currency=VND,
    //                         idempotencyKey="order-"+orderId
    // -----------------------------------------------------------------------

    @Test
    void acRealCreate_requestBodyContainsAllRequiredFields() throws Exception {
        String responseBody = """
                {
                  "success": true,
                  "data": {"paymentId": "P3", "paymentUrl": "http://pay.example.com/tx/P3"},
                  "error": null,
                  "requestId": "req-003"
                }
                """;

        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.amount").value(AMOUNT))
                .andExpect(jsonPath("$.currency").value("VND"))
                .andExpect(jsonPath("$.idempotencyKey").value("order-" + ORDER_ID))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        client.createTransaction(cmd, BEARER_TOKEN);

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-unwrap-paymentId: data.paymentId (not transactionId) maps to PaymentResult.transactionId
    // -----------------------------------------------------------------------

    @Test
    void acUnwrapPaymentId_dataPaymentIdFieldMapsToTransactionId() throws Exception {
        String responseBody = """
                {
                  "success": true,
                  "data": {
                    "paymentId": "EXACT-PAYMENT-ID",
                    "paymentUrl": "http://example.com/pay"
                  },
                  "error": null,
                  "requestId": "req-004"
                }
                """;

        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        PaymentResult result = client.createTransaction(cmd, BEARER_TOKEN);

        mockServer.verify();
        assertThat(result.transactionId())
                .as("data.paymentId must map to PaymentResult.transactionId — NOT data.transactionId")
                .isEqualTo("EXACT-PAYMENT-ID");
    }

    // -----------------------------------------------------------------------
    // AC-payment-down-5xx: 503 → PaymentUnavailableException
    // -----------------------------------------------------------------------

    @Test
    void acPaymentDown5xx_503_throwsPaymentUnavailableException() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"error\":\"service unavailable\"}"));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> client.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class);

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-payment-4xx: 400 → PaymentUnavailableException (log "rejected request")
    // -----------------------------------------------------------------------

    @Test
    void acPayment4xx_400_throwsPaymentUnavailableException() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"bad input\"}}"));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> client.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class)
                .as("4xx from payment must throw PaymentUnavailableException, not a business exception");

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-payment-4xx extra: 422 → PaymentUnavailableException
    // -----------------------------------------------------------------------

    @Test
    void acPayment4xx_422_throwsPaymentUnavailableException() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"error\":{\"code\":\"DUPLICATE_TX\",\"message\":\"duplicate\"}}"));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> client.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class);

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-payment-connect: I/O error (simulated via withServerError) → PaymentUnavailableException
    // Note: full connect-refused requires a separate RealPaymentClient instance pointing at
    // a dead port (no injection needed — new instance, no MockRestServiceServer involvement).
    // -----------------------------------------------------------------------

    @Test
    void acPaymentConnect_serverErrorSimulatesInfraFailure_throwsPaymentUnavailableException() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> client.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class);

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-payment-connect (connect refused): create a new client pointing at a dead port.
    // No MockRestServiceServer needed — the client never reaches the server.
    // -----------------------------------------------------------------------

    @Test
    void acPaymentConnect_connectionRefused_throwsPaymentUnavailableException() {
        // Port 19999 is unlikely to be listening; no stub needed — connect attempt will fail.
        RealPaymentClient deadClient = new RealPaymentClient(
                "http://localhost:19999", PAYMENT_PATH, MAPPER);

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> deadClient.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    // -----------------------------------------------------------------------
    // AC-parse-fail: 200 with garbage body → PaymentUnavailableException
    // -----------------------------------------------------------------------

    @Test
    void acParseFail_200WithGarbageBody_throwsPaymentUnavailableException() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("this is not json at all !!!", MediaType.APPLICATION_JSON));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> client.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class);

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-parse-fail extra: 200 with null data field → PaymentUnavailableException (FIXED)
    // parsePaymentResult now checks paymentId == null || blank → throws.
    // -----------------------------------------------------------------------

    @Test
    void acParseFail_200WithNullData_throwsPaymentUnavailableException() {
        // data is JSON null — paymentId resolved as null → must throw
        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":null,\"error\":null,\"requestId\":\"r\"}",
                        MediaType.APPLICATION_JSON));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> client.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class)
                .as("200 with data=null must throw PaymentUnavailableException, not return empty transactionId");

        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // AC-parse-fail extra: 200 with data present but paymentId field absent → PaymentUnavailableException
    // -----------------------------------------------------------------------

    @Test
    void acParseFail_200WithDataMissingPaymentId_throwsPaymentUnavailableException() {
        // data object exists but has no paymentId key → asText(null) returns null → must throw
        mockServer.expect(requestTo(DUMMY_BASE_URL + PAYMENT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"paymentUrl\":\"http://pay.example.com/tx/X\"},\"error\":null,\"requestId\":\"r\"}",
                        MediaType.APPLICATION_JSON));

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                ORDER_ID, USER_ID, AMOUNT, "VND", "order-" + ORDER_ID);

        assertThatThrownBy(() -> client.createTransaction(cmd, BEARER_TOKEN))
                .isInstanceOf(PaymentUnavailableException.class)
                .as("200 with data present but paymentId absent must throw PaymentUnavailableException");

        mockServer.verify();
    }

    @Test
    void refund_requestShapeAndSuccessfulResponse_areMapped() {
        UUID transactionId = UUID.randomUUID();
        mockServer.expect(requestTo(DUMMY_BASE_URL + REFUND_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.refundRequestId").value("refund-" + ORDER_ID))
                .andExpect(jsonPath("$.amount").value(AMOUNT))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"status\":\"REFUNDED\",\"refundGatewayRef\":\"GW-123\",\"paymentTransactionId\":\""
                                + transactionId + "\"}}",
                        MediaType.APPLICATION_JSON));

        RefundResponse response = client.refund(new RefundRequest(ORDER_ID, "refund-" + ORDER_ID, AMOUNT));

        assertThat(response.status()).isEqualTo("REFUNDED");
        assertThat(response.refundGatewayRef()).isEqualTo("GW-123");
        assertThat(response.paymentTransactionId()).isEqualTo(transactionId);
        mockServer.verify();
    }

    @Test
    void refund_recognized422_preservesTaxonomy() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + REFUND_PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"REFUND_AMOUNT_MISMATCH\",\"message\":\"bad amount\"}}"));

        assertThatThrownBy(() -> client.refund(new RefundRequest(ORDER_ID, "refund-" + ORDER_ID, AMOUNT)))
                .isInstanceOfSatisfying(PaymentRefundException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(422);
                    assertThat(exception.getErrorCode()).isEqualTo("REFUND_AMOUNT_MISMATCH");
                });
        mockServer.verify();
    }

    @Test
    void refund_503_isRetryableUnavailable() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + REFUND_PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"PAYMENT_GATEWAY_UNAVAILABLE\",\"message\":\"later\"}}"));

        assertThatThrownBy(() -> client.refund(new RefundRequest(ORDER_ID, "refund-" + ORDER_ID, AMOUNT)))
                .isInstanceOf(PaymentUnavailableException.class);
        mockServer.verify();
    }

    @Test
    void refund_malformed200_isRetryableUnavailable() {
        mockServer.expect(requestTo(DUMMY_BASE_URL + REFUND_PATH))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"status\":\"REFUNDED\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refund(new RefundRequest(ORDER_ID, "refund-" + ORDER_ID, AMOUNT)))
                .isInstanceOf(PaymentUnavailableException.class);
        mockServer.verify();
    }
}
