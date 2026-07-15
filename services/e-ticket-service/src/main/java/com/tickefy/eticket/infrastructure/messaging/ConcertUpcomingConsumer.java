package com.tickefy.eticket.infrastructure.messaging;

import com.tickefy.eticket.modules.ticket.entity.Ticket;
import com.tickefy.eticket.modules.ticket.entity.TicketStatus;
import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bridges event-service 24h concert reminders to notification-service.
 *
 * <p>event-service owns concert timing and publishes ConcertUpcoming.
 * ticket-service owns ticket ownership, so it fans out one
 * TicketReminderRequested event per user with issued tickets.
 */
@Component
public class ConcertUpcomingConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConcertUpcomingConsumer.class);

    private final TicketRepository ticketRepository;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public ConcertUpcomingConsumer(
            TicketRepository ticketRepository,
            RabbitTemplate rabbitTemplate,
            @Value("${app.messaging.exchange:tickefy.exchange}") String exchange) {
        this.ticketRepository = ticketRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    @RabbitListener(queues = "${app.messaging.queue.concert-upcoming:ticket-service.concert-upcoming.queue}")
    public void onConcertUpcoming(ConcertUpcomingEvent event) {
        ConcertUpcomingEvent.Payload payload = event.payload();
        if (payload == null || payload.concertId() == null || payload.concertId().isBlank()) {
            throw new IllegalArgumentException("concert.upcoming missing payload.concertId");
        }

        List<Ticket> tickets = ticketRepository.findByConcertIdAndStatus(payload.concertId(), TicketStatus.ISSUED);
        Map<String, Long> ticketCountByUser = tickets.stream()
                .collect(Collectors.groupingBy(Ticket::getUserId, Collectors.counting()));

        Instant requestedAt = Instant.now();
        ticketCountByUser.forEach((userId, ticketCount) -> {
            TicketReminderRequestedEvent reminder = TicketReminderRequestedEvent.from(
                    event,
                    payload,
                    userId,
                    Math.toIntExact(ticketCount),
                    requestedAt);
            rabbitTemplate.convertAndSend(exchange, "ticket.reminder-requested", reminder);
        });

        log.info("Processed ConcertUpcoming messageId={} concertId={} reminderUsers={}",
                event.messageId(), payload.concertId(), ticketCountByUser.size());
    }
}
