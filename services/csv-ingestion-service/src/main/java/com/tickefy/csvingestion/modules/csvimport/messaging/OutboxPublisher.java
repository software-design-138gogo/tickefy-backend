package com.tickefy.csvingestion.modules.csvimport.messaging;

import com.tickefy.csvingestion.modules.csvimport.entity.OutboxEntity;
import com.tickefy.csvingestion.modules.csvimport.event.CsvEvents;
import com.tickefy.csvingestion.modules.csvimport.repository.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox drainer: polls PENDING rows, publishes them to {@code tickefy.exchange} with the routing key
 * derived from eventType, then marks PUBLISHED.
 *
 * <p>Publish runs OUTSIDE any TX (§8): read page → send (network) → mark via a short repository TX.
 * At-least-once: a broker-down publish leaves the row PENDING (retried next tick); a crash between
 * send and mark re-publishes the SAME payload (messageId unchanged) so consumers dedup (§6.9).
 */
@Component
@ConditionalOnProperty(name = "app.messaging.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 50;

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${app.messaging.exchange:tickefy.exchange}")
    private String exchange;

    public OutboxPublisher(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${app.messaging.outbox.poll-ms:5000}")
    public void drain() {
        List<OutboxEntity> pending =
                outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, BATCH));
        for (OutboxEntity row : pending) {
            String routingKey = deriveRoutingKey(row.getEventType());
            if (routingKey == null) {
                log.error("Outbox unknown eventType={} id={} — marking FAILED", row.getEventType(), row.getId());
                outboxRepository.markEventFailed(row.getId());
                continue;
            }
            try {
                Message message = MessageBuilder
                        .withBody(row.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(row.getId().toString())
                        .build();
                rabbitTemplate.send(exchange, routingKey, message); // network — outside any TX (§8)
                outboxRepository.markPublished(row.getId(), Instant.now());
                log.info("Outbox published id={} type={} rk={}", row.getId(), row.getEventType(), routingKey);
            } catch (Exception e) {
                // Leave PENDING; retried next poll (at-least-once).
                log.warn("Outbox publish failed id={} type={} — keep PENDING: {}",
                        row.getId(), row.getEventType(), e.getMessage());
            }
        }
    }

    /** Map eventType -> routing key per the 5a EVENT CONTRACT. Unknown type -> null (mark FAILED, never publish). */
    private String deriveRoutingKey(String eventType) {
        return switch (eventType) {
            case CsvEvents.EventType.VIP_GUEST_IMPORT_COMPLETED -> CsvEvents.RoutingKey.COMPLETED;
            case CsvEvents.EventType.VIP_GUEST_IMPORT_FAILED -> CsvEvents.RoutingKey.FAILED;
            default -> null;
        };
    }
}
