package com.tickefy.eticket.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConcertCancelledConsumerTest {

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private ConcertCancelledConsumer consumer;

    private ConcertCancelledEvent event() {
        return new ConcertCancelledEvent(
                "event-msg-1",
                "ConcertCancelled",
                "1.0",
                "event-service",
                "2026-06-26T10:00:00Z",
                "corr-1",
                null,
                new ConcertCancelledEvent.Payload(
                        "11111111-1111-4111-8111-111111111111",
                        Instant.parse("2026-06-26T10:00:00Z"),
                        "Organizer cancelled the concert."));
    }

    @Test
    void onConcertCancelled_cancelsIssuedTicketsForConcert() {
        when(ticketRepository.cancelIssuedTicketsByConcertId(
                org.mockito.ArgumentMatchers.eq("11111111-1111-4111-8111-111111111111"),
                any(Instant.class)))
                .thenReturn(3);

        consumer.onConcertCancelled(event());

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(ticketRepository).cancelIssuedTicketsByConcertId(
                org.mockito.ArgumentMatchers.eq("11111111-1111-4111-8111-111111111111"),
                instantCaptor.capture());
    }

    @Test
    void onConcertCancelled_whenMissingConcertId_rethrowsForDlq() {
        ConcertCancelledEvent malformed = new ConcertCancelledEvent(
                "event-msg-1",
                "ConcertCancelled",
                "1.0",
                "event-service",
                "2026-06-26T10:00:00Z",
                "corr-1",
                null,
                new ConcertCancelledEvent.Payload(null, Instant.now(), "bad payload"));

        assertThatThrownBy(() -> consumer.onConcertCancelled(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload.concertId");
    }
}
