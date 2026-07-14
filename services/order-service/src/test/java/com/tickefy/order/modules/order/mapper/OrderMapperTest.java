package com.tickefy.order.modules.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.order.modules.order.dto.OrderResponse;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.entity.OrderItemEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies OrderMapper.toResponse now exposes the concert/ticket-type context the FE needs
 * (concertId on the order + ticketTypeName on each item) while keeping every legacy field intact.
 */
@Tag("unit")
class OrderMapperTest {

    private final OrderMapper mapper = new OrderMapper();

    @Test
    void toResponse_exposesConcertIdAndItemTicketTypeName_keepingLegacyFields() {
        UUID orderId = UUID.fromString("11111111-1111-4111-8111-000000000001");
        UUID concertId = UUID.fromString("c1c1c1c1-0000-4000-8000-000000000001");
        UUID ticketTypeId = UUID.fromString("dddd0001-0000-4000-8000-000000000001");
        Instant expiresAt = Instant.parse("2026-07-15T12:00:00Z");

        OrderItemEntity item = OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .ticketTypeId(ticketTypeId)
                .ticketTypeName("SVIP")
                .quantity(2)
                .unitPrice(3_500_000L)
                .build();
        OrderEntity order = OrderEntity.builder()
                .id(orderId)
                .concertId(concertId)
                .status("PAYMENT_PENDING")
                .totalAmount(7_000_000L)
                .paymentUrl("https://pay.stub/tx")
                .expiresAt(expiresAt)
                .items(List.of(item))
                .build();

        OrderResponse res = mapper.toResponse(order);

        // NEW context fields — the whole point of this change.
        assertThat(res.concertId()).isEqualTo(concertId);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).ticketTypeName()).isEqualTo("SVIP");

        // Legacy fields unchanged (backward-compat).
        assertThat(res.orderId()).isEqualTo(orderId);
        assertThat(res.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(res.totalAmount()).isEqualTo(7_000_000L);
        assertThat(res.paymentUrl()).isEqualTo("https://pay.stub/tx");
        assertThat(res.expiresAt()).isEqualTo(expiresAt);
        assertThat(res.items().get(0).ticketTypeId()).isEqualTo(ticketTypeId);
        assertThat(res.items().get(0).quantity()).isEqualTo(2);
        assertThat(res.items().get(0).unitPrice()).isEqualTo(3_500_000L);
    }
}
