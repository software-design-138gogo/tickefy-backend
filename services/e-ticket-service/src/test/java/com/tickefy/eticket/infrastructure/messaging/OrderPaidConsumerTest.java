package com.tickefy.eticket.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.eticket.modules.ticket.dto.IssueRequest;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.service.TicketService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests for OrderPaidConsumer — no Spring context, no real RabbitMQ.
 * All dependencies are mocked with Mockito.
 */
@ExtendWith(MockitoExtension.class)
class OrderPaidConsumerTest {

    @Mock
    private TicketService ticketService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderPaidConsumer consumer;

    private static final String EXCHANGE = "tickefy.exchange";

    private void injectExchange() {
        ReflectionTestUtils.setField(consumer, "exchange", EXCHANGE);
    }

    private TicketDto sampleTicket(String orderItemId, String concertId) {
        return new TicketDto(
                UUID.randomUUID(), "order-1", orderItemId, "user-1",
                concertId, "type-1", "GA", "General Admission",
                "ISSUED", "qr-****" + orderItemId, null, Instant.now()
        );
    }

    /** Build an ENVELOPE order.paid event with the given items. */
    private OrderPaidEvent envelope(List<OrderPaidEvent.OrderItem> items) {
        var payload = new OrderPaidEvent.Payload("order-1", "user-1", "concert-1", "2026-06-16T10:00:00Z", items);
        return new OrderPaidEvent("msg-1", "OrderPaid", "1.0", "order-service",
                "2026-06-16T10:00:00Z", "corr-1", null, payload);
    }

    private OrderPaidEvent.OrderItem item(String orderItemId, String typeId, int quantity, String zone, String name) {
        return new OrderPaidEvent.OrderItem(orderItemId, typeId, quantity, zone, name);
    }

    @Test
    void onOrderPaid_singleItem_issuesTicketAndPublishesEvent() {
        injectExchange();
        var event = envelope(List.of(item("item-1", "type-1", 1, "GA", "General Admission")));

        when(ticketService.issueTicket(any(), eq(1))).thenReturn(sampleTicket("item-1", "concert-1"));

        consumer.onOrderPaid(event);

        // Verify issueTicket called with correct request
        var captor = ArgumentCaptor.forClass(IssueRequest.class);
        verify(ticketService).issueTicket(captor.capture(), eq(1));
        IssueRequest req = captor.getValue();
        assertThat(req.orderId()).isEqualTo("order-1");
        assertThat(req.orderItemId()).isEqualTo("item-1");
        assertThat(req.userId()).isEqualTo("user-1");
        assertThat(req.concertId()).isEqualTo("concert-1");

        // Verify tickets.issued batch event published
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("tickets.issued"), any(TicketsIssuedEvent.class));
    }

    @Test
    void onOrderPaid_multipleItems_issuesAllTickets() {
        injectExchange();
        var event = envelope(List.of(
                item("item-1", "type-1", 1, "GA", "General Admission"),
                item("item-2", "type-2", 1, "VIP", "VIP Section")));

        when(ticketService.issueTicket(any(), eq(1)))
                .thenReturn(sampleTicket("item-1", "concert-1"))
                .thenReturn(sampleTicket("item-2", "concert-1"));

        consumer.onOrderPaid(event);

        verify(ticketService, times(2)).issueTicket(any(), eq(1));
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("tickets.issued"), any(TicketsIssuedEvent.class));
    }

    @Test
    void onOrderPaid_qtyGreaterThanOne_issuesOneTicketPerSeat() {
        injectExchange();
        var event = envelope(List.of(item("item-1", "type-1", 3, "GA", "General Admission")));

        when(ticketService.issueTicket(any(), anyInt())).thenReturn(sampleTicket("item-1", "concert-1"));

        consumer.onOrderPaid(event);

        // qty=3 → 3 tickets, seatSequence 1/2/3
        var seqCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(ticketService, times(3)).issueTicket(any(), seqCaptor.capture());
        assertThat(seqCaptor.getAllValues()).containsExactly(1, 2, 3);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("tickets.issued"), any(TicketsIssuedEvent.class));
    }

    @Test
    void onOrderPaid_idempotent_doesNotDuplicateOnReplay() {
        injectExchange();
        var event = envelope(List.of(item("item-1", "type-1", 1, "GA", "General Admission")));

        TicketDto existing = sampleTicket("item-1", "concert-1");
        when(ticketService.issueTicket(any(), eq(1))).thenReturn(existing);

        // Simulate replay (call twice)
        consumer.onOrderPaid(event);
        consumer.onOrderPaid(event);

        // TicketService called twice but returns same ticket (idempotent)
        verify(ticketService, times(2)).issueTicket(any(), eq(1));
        // Two tickets.issued attempts, both with the same stable child messageId.
        var captor = ArgumentCaptor.forClass(TicketsIssuedEvent.class);
        verify(rabbitTemplate, times(2)).convertAndSend(eq(EXCHANGE), eq("tickets.issued"), captor.capture());
        assertThat(captor.getAllValues()).extracting(TicketsIssuedEvent::messageId).containsOnly(captor.getValue().messageId());
    }

    @Test
    void onOrderPaid_serviceThrows_rethrowsForRedelivery() {
        injectExchange();
        var event = envelope(List.of(item("item-1", "type-1", 1, "GA", "General Admission")));

        doThrow(new RuntimeException("DB error")).when(ticketService).issueTicket(any(), anyInt());

        assertThatThrownBy(() -> consumer.onOrderPaid(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB error");

        // No tickets.issued event published on failure
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void onOrderPaid_ticketIssuedEvent_hasCorrectFields() {
        injectExchange();
        var event = envelope(List.of(item("item-1", "type-1", 1, "GA", "General Admission")));

        UUID ticketId = UUID.randomUUID();
        TicketDto ticket = new TicketDto(
                ticketId, "order-1", "item-1", "user-1",
                "concert-1", "type-1", "GA", "General Admission",
                "ISSUED", "qr-****-abc", null, Instant.now()
        );
        when(ticketService.issueTicket(any(), eq(1))).thenReturn(ticket);

        consumer.onOrderPaid(event);

        var captor = ArgumentCaptor.forClass(TicketsIssuedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("tickets.issued"), captor.capture());

        TicketsIssuedEvent issued = captor.getValue();
        assertThat(issued.eventType()).isEqualTo("TicketsIssued");
        assertThat(issued.eventVersion()).isEqualTo("1.0");
        assertThat(issued.source()).isEqualTo("ticket-service");
        assertThat(issued.correlationId()).isEqualTo("corr-1");
        assertThat(issued.causationId()).isEqualTo("msg-1");
        assertThat(issued.payload().orderId()).isEqualTo("order-1");
        assertThat(issued.payload().userId()).isEqualTo("user-1");
        assertThat(issued.payload().concertId()).isEqualTo("concert-1");
        assertThat(issued.payload().issuedAt()).isNotNull();
        assertThat(issued.payload().tickets()).hasSize(1);
        TicketsIssuedEvent.TicketItem item = issued.payload().tickets().get(0);
        assertThat(item.ticketId()).isEqualTo(ticketId.toString());
        assertThat(item.orderItemId()).isEqualTo("item-1");
        assertThat(item.ticketTypeId()).isEqualTo("type-1");
        assertThat(item.ticketTypeName()).isEqualTo("General Admission");
        assertThat(item.status()).isEqualTo("ISSUED");
    }
}
