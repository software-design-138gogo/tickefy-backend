package com.tickefy.event.modules.outbox;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDrainer {

    private final OutboxEventRepository outboxEventRepository;
    private final AmqpTemplate amqpTemplate;

    @Value("${app.rabbitmq.exchange:tickefy.events}")
    private String exchange;

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay:5000}")
    @Transactional
    public void drainOutbox() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                // Determine routing key based on event type
                String routingKey = event.getEventType();
                
                amqpTemplate.convertAndSend(exchange, routingKey, event.getPayload());

                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
                
                log.debug("Published event ID {} with type {}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("Failed to publish event ID {}: {}", event.getId(), e.getMessage(), e);
                // Stop processing further events to maintain order, or continue. 
                // We break to retry next time.
                break;
            }
        }
    }
}
