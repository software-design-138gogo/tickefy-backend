package com.tickefy.eticket.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.eticket.modules.ticket.entity.Ticket;
import com.tickefy.eticket.modules.ticket.entity.TicketStatus;
import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConcertUpcomingConsumerTest {

    private static final String EXCHANGE = "tickefy.exchange";

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private ConcertUpcomingConsumer consumer;

    private void injectExchange() {
        ReflectionTestUtils.setField(consumer, "exchange", EXCHANGE);
    }

    private ConcertUpcomingEvent event() {
        return new ConcertUpcomingEvent(
                "event-msg-1",
                "ConcertUpcoming",
                "1.0",
                "event-service",
                "2026-06-26T10:00:00Z",
                "corr-1",
                null,
                new ConcertUpcomingEvent.Payload(
                        "11111111-1111-4111-8111-111111111111",
                        "Tickefy Live",
                        Instant.parse("2026-06-27T10:00:00Z")));
    }

    private Ticket ticket(String userId) {
        Ticket ticket = new Ticket();
        ticket.setUserId(userId);
        ticket.setConcertId("11111111-1111-4111-8111-111111111111");
        ticket.setStatus(TicketStatus.ISSUED);
        ticket.setOrderId("order-1");
        ticket.setOrderItemId("item-1");
        ticket.setSeatSequence(1);
        ticket.setQrToken("raw-token-test");
        return ticket;
    }

    @Test
    void onConcertUpcoming_publishesOneReminderPerUserWithTicketCounts() {
        injectExchange();
        when(ticketRepository.findByConcertIdAndStatus(
                "11111111-1111-4111-8111-111111111111",
                TicketStatus.ISSUED))
                .thenReturn(List.of(
                        ticket("11111111-1111-4111-8111-000000000001"),
                        ticket("11111111-1111-4111-8111-000000000001"),
                        ticket("11111111-1111-4111-8111-000000000002")));

        consumer.onConcertUpcoming(event());

        var captor = ArgumentCaptor.forClass(TicketReminderRequestedEvent.class);
        verify(rabbitTemplate, times(2))
                .convertAndSend(eq(EXCHANGE), eq("ticket.reminder-requested"), captor.capture());

        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues())
                .extracting(TicketReminderRequestedEvent::eventType)
                .containsOnly("TicketReminderRequested");
        assertThat(captor.getAllValues())
                .extracting(TicketReminderRequestedEvent::source)
                .containsOnly("ticket-service");
        assertThat(captor.getAllValues())
                .extracting(TicketReminderRequestedEvent::correlationId)
                .containsOnly("corr-1");
        assertThat(captor.getAllValues())
                .extracting(TicketReminderRequestedEvent::causationId)
                .containsOnly("event-msg-1");
        assertThat(captor.getAllValues())
                .extracting(event -> event.payload().ticketCount())
                .containsExactlyInAnyOrder(2, 1);
    }

    @Test
    void onConcertUpcoming_whenNoTickets_doesNotPublishReminder() {
        injectExchange();
        when(ticketRepository.findByConcertIdAndStatus(
                "11111111-1111-4111-8111-111111111111",
                TicketStatus.ISSUED))
                .thenReturn(List.of());

        consumer.onConcertUpcoming(event());

        verify(rabbitTemplate, never()).convertAndSend(
                eq(EXCHANGE),
                eq("ticket.reminder-requested"),
                org.mockito.ArgumentMatchers.any(TicketReminderRequestedEvent.class));
    }

    @Test
    void onConcertUpcoming_whenMissingConcertId_rethrowsForDlq() {
        ConcertUpcomingEvent malformed = new ConcertUpcomingEvent(
                "event-msg-1",
                "ConcertUpcoming",
                "1.0",
                "event-service",
                "2026-06-26T10:00:00Z",
                "corr-1",
                null,
                new ConcertUpcomingEvent.Payload(null, "Tickefy Live", Instant.now()));

        assertThatThrownBy(() -> consumer.onConcertUpcoming(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload.concertId");
    }
}
