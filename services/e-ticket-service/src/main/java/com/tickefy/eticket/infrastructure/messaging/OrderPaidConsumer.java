package com.tickefy.eticket.infrastructure.messaging;

import com.tickefy.eticket.modules.ticket.dto.IssueRequest;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.service.TicketService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Consumes order.paid events from RabbitMQ and issues tickets.
 *
 * <p>Idempotency: TicketService.issueTicket() is idempotent on orderItemId.
 * Duplicate messages (redelivery) will return the existing ticket without
 * creating duplicates.
 *
 * <p>After issuing each ticket, publishes a ticket.issued event so
 * notification-service can send the ticket to the customer.
 */
@Component
public class OrderPaidConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidConsumer.class);

    private final TicketService ticketService;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public OrderPaidConsumer(
            TicketService ticketService,
            RabbitTemplate rabbitTemplate,
            @Value("${app.messaging.exchange:tickefy.exchange}") String exchange) {
        this.ticketService = ticketService;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    /**
     * Listens on the order-paid queue.
     * Processes each order item as a separate ticket issuance.
     * The entire listener method is NOT @Transactional — each item is
     * processed independently so a failure on one item doesn't roll back others.
     */
    @RabbitListener(queues = "${app.messaging.queue.order-paid:ticket-service.order-paid.queue}")
    public void onOrderPaid(OrderPaidEvent event) {
        log.info("Received order.paid orderId={} concertId={} items={}",
                event.orderId(), event.concertId(), event.items().size());

        for (OrderPaidEvent.OrderItem item : event.items()) {
            try {
                processItem(event, item);
            } catch (Exception ex) {
                // Log and continue — other items should still be processed.
                // The failed item will be retried via RabbitMQ redelivery / DLQ.
                log.error("Failed to issue ticket for orderItemId={} orderId={}: {}",
                        item.orderItemId(), event.orderId(), ex.getMessage(), ex);
                throw ex; // re-throw so RabbitMQ handles redelivery
            }
        }
    }

    private void processItem(OrderPaidEvent event, OrderPaidEvent.OrderItem item) {
        IssueRequest req = new IssueRequest(
                event.orderId(),
                item.orderItemId(),
                event.userId(),
                event.concertId(),
                item.ticketTypeId(),
                item.zoneId(),
                item.ticketTypeName()
        );

        TicketDto ticket = ticketService.issueTicket(req);

        // Publish ticket.issued for notification-service
        TicketIssuedEvent issued = new TicketIssuedEvent(
                ticket.id().toString(),
                ticket.orderId(),
                ticket.orderItemId(),
                ticket.userId(),
                ticket.concertId(),
                ticket.qrToken(),
                Instant.now()
        );
        rabbitTemplate.convertAndSend(exchange, "ticket.issued", issued);

        log.info("Ticket issued and event published ticketId={} orderItemId={}",
                ticket.id(), item.orderItemId());
    }
}
