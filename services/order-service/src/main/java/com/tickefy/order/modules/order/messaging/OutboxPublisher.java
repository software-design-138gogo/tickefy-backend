package com.tickefy.order.modules.order.messaging;

import com.tickefy.order.modules.order.entity.OutboxEntity;
import com.tickefy.order.modules.order.repository.OutboxRepository;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox drainer: polls PENDING rows and publishes them to {@code tickefy.exchange} with the routing
 * key derived from eventType, then marks PUBLISHED.
 *
 * <p>At-least-once: if the app crashes between publish and mark, the row re-publishes next poll —
 * consumers are idempotent. Payload is already-serialized JSON; sent as a raw application/json body so
 * a flat-shape consumer (e-ticket) deserializes it directly.
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

    @Scheduled(fixedDelayString = "${app.messaging.outbox-poll-delay:3000}")
    @Transactional
    public void drain() {
        List<OutboxEntity> pending =
                outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, BATCH));
        if (pending.isEmpty()) {
            return;
        }
        for (OutboxEntity row : pending) {
            String routingKey = routingKeyFor(row.getEventType());
            if (routingKey == null) {
                log.error("Outbox row id={} has unknown eventType={} — marking FAILED", row.getId(), row.getEventType());
                row.setStatus("FAILED");
                outboxRepository.save(row);
                continue;
            }
            try {
                Message message = MessageBuilder
                        .withBody(row.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(row.getId().toString())
                        .build();
                rabbitTemplate.send(exchange, routingKey, message);
                row.setStatus("PUBLISHED");
                row.setPublishedAt(Instant.now());
                outboxRepository.save(row);
                log.info("Outbox published id={} type={} rk={}", row.getId(), row.getEventType(), routingKey);
            } catch (Exception e) {
                // Leave PENDING; retried next poll.
                log.warn("Outbox publish failed id={} type={} — will retry: {}", row.getId(), row.getEventType(), e.getMessage());
            }
        }
    }

    private String routingKeyFor(String eventType) {
        return switch (eventType) {
            case OrderEvents.Type.ORDER_PAID -> OrderEvents.RoutingKey.ORDER_PAID;
            case OrderEvents.Type.ORDER_PAYMENT_FAILED -> OrderEvents.RoutingKey.ORDER_PAYMENT_FAILED;
            case OrderEvents.Type.ORDER_EXPIRED -> OrderEvents.RoutingKey.ORDER_EXPIRED;
            default -> null;
        };
    }
}
