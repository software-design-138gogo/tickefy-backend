package com.tickefy.payment.modules.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickefy.payment.BaseRefundIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F3: gateway business-decline. refund-mode=REJECT makes MockSePay return a REJECTED result (a
 * VALUE, not an exception — R1). Expect 422 REFUND_REJECTED and the tx left SUCCESS (not REFUNDED).
 */
@TestPropertySource(properties = {"app.sepay.mock.refund-mode=REJECT"})
class PaymentRefundRejectIntegrationTest extends BaseRefundIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_transactions");
    }

    @Test
    void f3_reject_returns422_txStaysSuccess() throws Exception {
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
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("REFUND_REJECTED"));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM payment_transactions WHERE id=?", String.class, paymentId))
                .as("REJECTED must leave tx SUCCESS").isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                        "SELECT refund_request_id FROM payment_transactions WHERE id=?",
                        String.class, paymentId))
                .as("no refund persisted on reject").isNull();
    }
}
