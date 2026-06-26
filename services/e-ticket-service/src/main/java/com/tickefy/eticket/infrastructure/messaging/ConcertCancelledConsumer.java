package com.tickefy.eticket.infrastructure.messaging;

import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes ConcertCancelled from event-service and invalidates issued tickets.
 */
@Component
public class ConcertCancelledConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConcertCancelledConsumer.class);

    private final TicketRepository ticketRepository;

    public ConcertCancelledConsumer(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @RabbitListener(queues = "${app.messaging.queue.concert-cancelled:ticket-service.concert-cancelled.queue}")
    @Transactional
    public void onConcertCancelled(ConcertCancelledEvent event) {
        ConcertCancelledEvent.Payload payload = event.payload();
        if (payload == null || payload.concertId() == null || payload.concertId().isBlank()) {
            throw new IllegalArgumentException("concert.cancelled missing payload.concertId");
        }

        int cancelled = ticketRepository.cancelIssuedTicketsByConcertId(payload.concertId(), Instant.now());
        log.info("Processed ConcertCancelled messageId={} concertId={} cancelledTickets={} reason={}",
                event.messageId(), payload.concertId(), cancelled, payload.reason());
    }
}
