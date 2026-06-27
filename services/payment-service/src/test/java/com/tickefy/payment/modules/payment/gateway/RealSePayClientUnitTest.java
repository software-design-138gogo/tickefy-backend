package com.tickefy.payment.modules.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tickefy.payment.modules.payment.gateway.SePayClient.CreateQrResult;
import com.tickefy.payment.modules.payment.gateway.SePayClient.QueryStatusResult;
import com.tickefy.payment.modules.payment.gateway.SePayClient.RefundResult;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@Tag("unit")
class RealSePayClientUnitTest {

    private static final String BASE_URL = "https://sepay.test";
    private static final String API_TOKEN = "test-sepay-token";
    private static final String ACCOUNT_NUMBER = "1234567890";
    private static final String BANK_CODE = "MBBank";
    private static final String ACCOUNT_NAME = "NGUYEN VAN A";
    private static final String QR_BASE_URL = "https://qr.sepay.vn/img";
    private static final String QR_TEMPLATE = "compact";

    private RealSePayClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new RealSePayClient(
                BASE_URL,
                API_TOKEN,
                ACCOUNT_NUMBER,
                BANK_CODE,
                ACCOUNT_NAME,
                QR_BASE_URL,
                QR_TEMPLATE,
                builder.build());
    }

    @Test
    void createQr_generatesSepayQrImageUrlAndUsesTransferContentAsGatewayOrderId() {
        UUID paymentId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID orderId = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef");

        CreateQrResult result = client.createQr(paymentId, 150_000L, "VND", orderId);

        assertThat(result.gatewayOrderId()).isEqualTo("TKF1234567890AB");
        assertThat(result.qrCodePayload()).isEqualTo(result.paymentUrl());
        assertThat(result.paymentUrl())
                .startsWith(QR_BASE_URL + "?")
                .contains("acc=1234567890")
                .contains("bank=MBBank")
                .contains("amount=150000")
                .contains("des=TKF1234567890AB")
                .contains("template=compact")
                .contains("showinfo=true")
                .contains("holder=NGUYEN+VAN+A");
    }

    @Test
    void queryStatus_whenNoTransactionMatches_returnsPending() {
        mockServer.expect(request -> assertTransactionsUri(request.getURI(), "TKFNOHIT123456"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_TOKEN))
                .andRespond(withSuccess(
                        """
                        {
                          "status": 200,
                          "transactions": []
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        QueryStatusResult result = client.queryStatus("TKFNOHIT123456");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.gatewayTransactionId()).isNull();
        mockServer.verify();
    }

    @Test
    void queryStatus_whenTransactionContentMatchesNormalizedIncomingAmount_returnsSuccess() {
        mockServer.expect(request -> assertTransactionsUri(request.getURI(), "TKFABCDEF123456"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "status": 200,
                          "transactions": [
                            {
                              "id": 92704,
                              "amount_in": 150000,
                              "amount_out": 0,
                              "transaction_content": "Thanh toan TKF-abcdef 123456",
                              "account_number": "1234567890",
                              "transaction_date": "2026-06-27 15:30:00",
                              "bank_brand_name": "MBBank",
                              "reference_number": "FT26178"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        QueryStatusResult result = client.queryStatus("TKFABCDEF123456");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.gatewayTransactionId()).isEqualTo("92704");
        mockServer.verify();
    }

    @Test
    void queryStatus_whenNewApiDataShapeMatches_returnsSuccess() {
        mockServer.expect(request -> assertTransactionsUri(request.getURI(), "TKFNEWAPI12345"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "status": "success",
                          "data": [
                            {
                              "id": "SPT123",
                              "amount_in": "250000",
                              "transfer_type": "in",
                              "transaction_content": "TKFNEWAPI12345",
                              "account_number": "1234567890",
                              "transaction_date": "2026-06-27T15:30:00+07:00",
                              "bank_brand_name": "MBBank",
                              "reference_number": "BNK123"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        QueryStatusResult result = client.queryStatus("TKFNEWAPI12345");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.gatewayTransactionId()).isEqualTo("SPT123");
        mockServer.verify();
    }

    @Test
    void queryStatusWithExpectedAmount_whenContentMatchesButAmountDiffers_returnsPending() {
        mockServer.expect(request -> assertTransactionsUri(request.getURI(), "TKFAMOUNT12345", 150_000L))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "status": 200,
                          "transactions": [
                            {
                              "id": 92705,
                              "amount_in": 149000,
                              "amount_out": 0,
                              "transaction_content": "TKFAMOUNT12345",
                              "account_number": "1234567890"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        QueryStatusResult result = client.queryStatus("TKFAMOUNT12345", 150_000L);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.gatewayTransactionId()).isNull();
        mockServer.verify();
    }

    @Test
    void queryStatus_whenSepayReturnsMainAccountButContentContainsVirtualAccount_returnsSuccess() {
        mockServer.expect(request -> assertTransactionsUri(request.getURI(), "TKFVIRTUAL1234", 10_000L))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "status": "success",
                          "data": [
                            {
                              "id": "e1db3b92-7247-11f1-b21a-a6006ab65aca",
                              "amount_in": 10000,
                              "transfer_type": "in",
                              "transaction_content": "Qakavr 1234567890 TKFVIRTUAL1234 CHUYEN TIEN",
                              "account_number": "00012112005000",
                              "transaction_date": "2026-06-27 23:47:27",
                              "reference_number": "FT26180853639235"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        QueryStatusResult result = client.queryStatus("TKFVIRTUAL1234", 10_000L);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.gatewayTransactionId()).isEqualTo("e1db3b92-7247-11f1-b21a-a6006ab65aca");
        mockServer.verify();
    }

    @Test
    void queryStatus_whenSepayContentContainsVirtualAccountWithoutVqrPrefix_returnsSuccess() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer vaMockServer = MockRestServiceServer.bindTo(builder).build();
        RealSePayClient vaClient = new RealSePayClient(
                BASE_URL,
                API_TOKEN,
                "VQRQAKAVR0630",
                BANK_CODE,
                ACCOUNT_NAME,
                QR_BASE_URL,
                QR_TEMPLATE,
                builder.build());

        vaMockServer.expect(request -> assertTransactionsUri(request.getURI(), "TKFC996906F4286", 10_000L))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "status": "success",
                          "data": [
                            {
                              "id": "e1db3b92-7247-11f1-b21a-a6006ab65aca",
                              "amount_in": 10000,
                              "transfer_type": "in",
                              "transaction_content": "Qakavr0630 SEPAY9808 135189930841-TKFC996906F4286-CHUYEN TIEN",
                              "account_number": "00012112005000",
                              "transaction_date": "2026-06-27 23:47:27",
                              "reference_number": "FT26180853639235"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        QueryStatusResult result = vaClient.queryStatus("TKFC996906F4286", 10_000L);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.gatewayTransactionId()).isEqualTo("e1db3b92-7247-11f1-b21a-a6006ab65aca");
        vaMockServer.verify();
    }

    @Test
    void queryStatus_whenSepayApiFails_throwsPaymentGatewayException() {
        mockServer.expect(request -> assertTransactionsUri(request.getURI(), "TKFERR123456"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.queryStatus("TKFERR123456"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("SePay API error");

        mockServer.verify();
    }

    @Test
    void refund_returnsRejectedForManualBankTransferRefund() {
        RefundResult result = client.refund("SPT123", 150_000L, "refund-123");

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.gatewayRef()).isNull();
        assertThat(result.reason()).isEqualTo("vietqr_manual_refund_required");
    }

    private static void assertTransactionsUri(URI uri, String transferContent) {
        assertTransactionsUri(uri, transferContent, null);
    }

    private static void assertTransactionsUri(URI uri, String transferContent, Long expectedAmount) {
        assertThat(uri.toString()).startsWith(BASE_URL + "/v2/transactions?");
        assertThat(uri.getQuery()).contains("q=" + transferContent);
        assertThat(uri.getQuery()).contains("per_page=50");
        assertThat(uri.getQuery()).contains("transfer_type=in");
        if (expectedAmount != null) {
            assertThat(uri.getQuery()).contains("amount_in_min=" + expectedAmount);
            assertThat(uri.getQuery()).contains("amount_in_max=" + expectedAmount);
        }
    }
}
