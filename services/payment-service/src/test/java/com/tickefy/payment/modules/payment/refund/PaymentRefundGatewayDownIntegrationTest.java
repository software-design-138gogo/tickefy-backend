package com.tickefy.payment.modules.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickefy.payment.BaseRefundIntegrationTest;
import com.tickefy.payment.modules.payment.gateway.PaymentGatewayException;
import com.tickefy.payment.modules.payment.gateway.SePayClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F4 (R2): gateway down. The mocked SePay delegate throws PaymentGatewayException; the REAL
 * PaymentGatewayClient @CircuitBreaker(name="sepayRefund") catches it via its fallback and emits
 * ApiException 503 — exercising the actual CB/fallback wiring without forcing the breaker OPEN.
 * Expect 503 PAYMENT_GATEWAY_UNAVAILABLE and the tx left SUCCESS (no refund persisted).
 */
class PaymentRefundGatewayDownIntegrationTest extends BaseRefundIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private SePayClient sePayClient;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_transactions");
    }

    @Test
    void f4_gatewayDown_returns503_txStaysSuccess() throws Exception {
        when(sePayClient.refund(anyString(), anyLong(), anyString()))
                .thenThrow(new PaymentGatewayException("mock gateway down"));

        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment_transactions (id, order_id, user_id, amount, currency,"
                        + " idempotency_key, gateway_order_id, gateway_transaction_id, status,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?, now(), now())",
                paymentId, orderId, UUID.randomUUID(), 150_000L, "VND",
                "order-" + orderId, "MOCK-" + paymentId, "GW-TXN-" + paymentId, "SUCCESS");

        mockMvc.perform(post("/internal/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"refundRequestId\":\"refund-"
                                + orderId + "\",\"amount\":150000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PAYMENT_GATEWAY_UNAVAILABLE"));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM payment_transactions WHERE id=?", String.class, paymentId))
                .as("gateway-down must leave tx SUCCESS").isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                        "SELECT refund_request_id FROM payment_transactions WHERE id=?",
                        String.class, paymentId))
                .isNull();
    }
}
