package com.tickefy.payment.modules.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickefy.payment.BaseRefundIntegrationTest;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Refund endpoint ITs on real Postgres (default mock = success). Covers:
 * F1 success (200 + tx REFUNDED + 3 cols set), F2 idempotent replay (no double-refund),
 * F5 no-SUCCESS-tx (409) / no-tx (404), F5b amount-mismatch (422 REFUND_AMOUNT_MISMATCH).
 * Gateway-down (F4) and reject (F3) live in sibling ITs that need distinct contexts.
 */
class PaymentRefundIntegrationTest extends BaseRefundIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_transactions");
    }

    private UUID seedTx(UUID orderId, String status, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment_transactions (id, order_id, user_id, amount, currency,"
                        + " idempotency_key, gateway_order_id, gateway_transaction_id, status,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?, now(), now())",
                id, orderId, UUID.randomUUID(), amount, "VND",
                "order-" + orderId, "MOCK-" + id, "GW-TXN-" + id, status);
        return id;
    }

    private String body(UUID orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"refundRequestId\":\"refund-" + orderId
                + "\",\"amount\":" + amount + "}";
    }

    // ---- F1 success ----
    @Test
    void f1_success_returns200_txRefunded_colsSet() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = seedTx(orderId, "SUCCESS", 150_000L);

        mockMvc.perform(post("/internal/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, 150_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.paymentTransactionId").value(paymentId.toString()))
                .andExpect(jsonPath("$.data.refundGatewayRef").value("MOCK-REFUND-refund-" + orderId));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM payment_transactions WHERE id=?", String.class, paymentId))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                        "SELECT refund_request_id FROM payment_transactions WHERE id=?",
                        String.class, paymentId))
                .isEqualTo("refund-" + orderId);
        assertThat(jdbc.queryForObject(
                        "SELECT refunded_at FROM payment_transactions WHERE id=?",
                        Timestamp.class, paymentId))
                .as("refunded_at must be set").isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT refund_gateway_ref FROM payment_transactions WHERE id=?",
                        String.class, paymentId))
                .isNotNull();
    }

    // ---- F2 idempotent: 2× same refundRequestId = exactly ONE refund ----
    @Test
    void f2_idempotent_secondCall_noDoubleRefund() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = seedTx(orderId, "SUCCESS", 150_000L);

        // 1st refund
        mockMvc.perform(post("/internal/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, 150_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));

        Timestamp firstRefundedAt = jdbc.queryForObject(
                "SELECT refunded_at FROM payment_transactions WHERE id=?", Timestamp.class, paymentId);
        String firstRef = jdbc.queryForObject(
                "SELECT refund_gateway_ref FROM payment_transactions WHERE id=?", String.class, paymentId);

        // 2nd refund — same refundRequestId → idempotent replay (no 2nd gateway refund)
        mockMvc.perform(post("/internal/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, 150_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.paymentTransactionId").value(paymentId.toString()));

        // Proof of no-double: still exactly ONE REFUNDED row, refunded_at + gateway_ref unchanged.
        Integer refundedRows = jdbc.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE order_id=? AND status='REFUNDED'",
                Integer.class, orderId);
        assertThat(refundedRows).as("exactly one refunded row").isEqualTo(1);
        Timestamp secondRefundedAt = jdbc.queryForObject(
                "SELECT refunded_at FROM payment_transactions WHERE id=?", Timestamp.class, paymentId);
        String secondRef = jdbc.queryForObject(
                "SELECT refund_gateway_ref FROM payment_transactions WHERE id=?", String.class, paymentId);
        assertThat(secondRefundedAt).as("refunded_at unchanged → no second refund").isEqualTo(firstRefundedAt);
        assertThat(secondRef).isEqualTo(firstRef);
    }

    // ---- F5 no SUCCESS tx → 409 ----
    @Test
    void f5_pendingOnly_returns409() throws Exception {
        UUID orderId = UUID.randomUUID();
        seedTx(orderId, "PENDING", 150_000L);

        mockMvc.perform(post("/internal/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, 150_000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    // ---- F5 no tx at all → 404 ----
    @Test
    void f5_noTransaction_returns404() throws Exception {
        UUID orderId = UUID.randomUUID(); // nothing seeded for this order

        mockMvc.perform(post("/internal/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, 150_000L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_FOUND"));
    }

    // ---- F5b amount mismatch → 422 ----
    @Test
    void f5b_amountMismatch_returns422_txStaysSuccess() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = seedTx(orderId, "SUCCESS", 150_000L);

        mockMvc.perform(post("/internal/payments/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, 999_000L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REFUND_AMOUNT_MISMATCH"));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM payment_transactions WHERE id=?", String.class, paymentId))
                .as("tx untouched on amount mismatch").isEqualTo("SUCCESS");
    }
}
