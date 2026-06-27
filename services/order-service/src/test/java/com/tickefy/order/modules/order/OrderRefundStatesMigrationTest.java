package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.order.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration IT for V4__order_refund_states: the refund statuses are accepted by chk_order_status
 * and a bogus status is still rejected. The fact that the Spring context boots at all proves V4
 * applied (Flyway) and ddl-validate passed against the OrderEntity (status column unchanged).
 */
class OrderRefundStatesMigrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID insertOrder(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orders (id, user_id, concert_id, status, idempotency_key, total_amount,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, UUID.randomUUID(), UUID.randomUUID(), status,
                "mig-it-" + id, 100000L);
        return id;
    }

    @Test
    void v4_allows_REFUND_PENDING() {
        UUID id = insertOrder("REFUND_PENDING");
        String got = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
        assertThat(got).isEqualTo("REFUND_PENDING");
    }

    @Test
    void v4_allows_REFUND_MANUAL_REVIEW() {
        UUID id = insertOrder("REFUND_MANUAL_REVIEW");
        String got = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
        assertThat(got).isEqualTo("REFUND_MANUAL_REVIEW");
    }

    @Test
    void v4_still_allows_legacy_PAID() {
        UUID id = insertOrder("PAID");
        String got = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
        assertThat(got).isEqualTo("PAID");
    }

    @Test
    void chk_order_status_rejects_bogus_status() {
        assertThatThrownBy(() -> insertOrder("BOGUS_STATUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
