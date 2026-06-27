package com.tickefy.inventory.modules.inventory.messaging;

import com.tickefy.inventory.modules.inventory.service.TicketTypeService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code concert.cancelled} (from event-service) and flags every ticket type of the cancelled
 * concert so {@code reserve} fails fast (emergency stop-sale, CLAUDE §6.3).
 *
 * <p>Idempotent via the bulk UPDATE (SET concert_cancelled=true): redelivery / re-publish re-sets the
 * same value — final state converges, no error. No processed-message table needed (state-convergence,
 * §6.9). A malformed message (missing payload.concertId) throws → dead-lettered (listener factory has
 * {@code setDefaultRequeueRejected(false)}), never requeued.
 */
@Component
public class ConcertCancelledConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConcertCancelledConsumer.class);

    private final TicketTypeService ticketTypeService;

    public ConcertCancelledConsumer(TicketTypeService ticketTypeService) {
        this.ticketTypeService = ticketTypeService;
    }

    @RabbitListener(queues = RabbitMqConfig.CONCERT_CANCELLED_QUEUE)
    public void onConcertCancelled(InventoryEvents.ConcertCancelledMessage event) {
        if (event == null || event.payload() == null || event.payload().concertId() == null) {
            throw new IllegalArgumentException("concert.cancelled missing payload.concertId");
        }
        UUID concertId = UUID.fromString(event.payload().concertId());
        int marked = ticketTypeService.markConcertCancelled(concertId);
        log.info("Received concert.cancelled messageId={} concertId={} ticketTypesMarked={}",
                safeMessageId(event), concertId, marked);
    }

    private String safeMessageId(InventoryEvents.ConcertCancelledMessage event) {
        return event == null ? null : event.messageId();
    }
}
