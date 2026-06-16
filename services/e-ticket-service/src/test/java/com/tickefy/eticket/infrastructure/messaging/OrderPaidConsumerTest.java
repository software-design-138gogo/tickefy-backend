package com.tickefy.eticket.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                "ISSUED", "qr-" + orderItemId, null, Instant.now()
        );
    }

    @Test
    void onOrderPaid_singleItem_issuesTicketAndPublishesEvent() {
        injectExchange();
        var item = new OrderPaidEvent.OrderItem("item-1", "type-1", "GA", "General Admission");
        var event = new OrderPaidEvent("order-1", "user-1", "concert-1", List.of(item));

        when(ticketService.issueTicket(any())).thenReturn(sampleTicket("item-1", "concert-1"));

        consumer.onOrderPaid(event);

        // Verify issueTicket called with correct request
        var captor = ArgumentCaptor.forClass(IssueRequest.class);
        verify(ticketService).issueTicket(captor.capture());
        IssueRequest req = captor.getValue();
        assertThat(req.orderId()).isEqualTo("order-1");
        assertThat(req.orderItemId()).isEqualTo("item-1");
        assertThat(req.userId()).isEqualTo("user-1");
        assertThat(req.concertId()).isEqualTo("concert-1");

        // Verify ticket.issued event published
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("ticket.issued"), any(TicketIssuedEvent.class));
    }

    @Test
    void onOrderPaid_multipleItems_issuesAllTickets() {
        injectExchange();
        var item1 = new OrderPaidEvent.OrderItem("item-1", "type-1", "GA", "General Admission");
        var item2 = new OrderPaidEvent.OrderItem("item-2", "type-2", "VIP", "VIP Section");
        var event = new OrderPaidEvent("order-1", "user-1", "concert-1", List.of(item1, item2));

        when(ticketService.issueTicket(any()))
                .thenReturn(sampleTicket("item-1", "concert-1"))
                .thenReturn(sampleTicket("item-2", "concert-1"));

        consumer.onOrderPaid(event);

        verify(ticketService, times(2)).issueTicket(any());
        verify(rabbitTemplate, times(2)).convertAndSend(eq(EXCHANGE), eq("ticket.issued"), any(TicketIssuedEvent.class));
    }

    @Test
    void onOrderPaid_idempotent_doesNotDuplicateOnReplay() {
        injectExchange();
        var item = new OrderPaidEvent.OrderItem("item-1", "type-1", "GA", "General Admission");
        var event = new OrderPaidEvent("order-1", "user-1", "concert-1", List.of(item));

        TicketDto existing = sampleTicket("item-1", "concert-1");
        when(ticketService.issueTicket(any())).thenReturn(existing);

        // Simulate replay (call twice)
        consumer.onOrderPaid(event);
        consumer.onOrderPaid(event);

        // TicketService called twice but returns same ticket (idempotent)
        verify(ticketService, times(2)).issueTicket(any());
        // Two ticket.issued events (notification-service is idempotent too)
        verify(rabbitTemplate, times(2)).convertAndSend(eq(EXCHANGE), eq("ticket.issued"), any(TicketIssuedEvent.class));
    }

    @Test
    void onOrderPaid_serviceThrows_rethrowsForRedelivery() {
        injectExchange();
        var item = new OrderPaidEvent.OrderItem("item-1", "type-1", "GA", "General Admission");
        var event = new OrderPaidEvent("order-1", "user-1", "concert-1", List.of(item));

        doThrow(new RuntimeException("DB error")).when(ticketService).issueTicket(any());

        assertThatThrownBy(() -> consumer.onOrderPaid(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB error");

        // No ticket.issued event published on failure
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void onOrderPaid_ticketIssuedEvent_hasCorrectFields() {
        injectExchange();
        var item = new OrderPaidEvent.OrderItem("item-1", "type-1", "GA", "General Admission");
        var event = new OrderPaidEvent("order-1", "user-1", "concert-1", List.of(item));

        UUID ticketId = UUID.randomUUID();
        TicketDto ticket = new TicketDto(
                ticketId, "order-1", "item-1", "user-1",
                "concert-1", "type-1", "GA", "General Admission",
                "ISSUED", "qr-abc", null, Instant.now()
        );
        when(ticketService.issueTicket(any())).thenReturn(ticket);

        consumer.onOrderPaid(event);

        var captor = ArgumentCaptor.forClass(TicketIssuedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("ticket.issued"), captor.capture());

        TicketIssuedEvent issued = captor.getValue();
        assertThat(issued.ticketId()).isEqualTo(ticketId.toString());
        assertThat(issued.orderId()).isEqualTo("order-1");
        assertThat(issued.orderItemId()).isEqualTo("item-1");
        assertThat(issued.userId()).isEqualTo("user-1");
        assertThat(issued.concertId()).isEqualTo("concert-1");
        assertThat(issued.qrToken()).isEqualTo("qr-abc");
        assertThat(issued.issuedAt()).isNotNull();
    }
}
