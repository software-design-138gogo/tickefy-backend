package com.tickefy.payment.modules.payment.messaging;

import static org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tickefy.payment.modules.payment.entity.OutboxEntity;
import com.tickefy.payment.modules.payment.repository.OutboxRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 50;

    @Value("${app.messaging.exchange:tickefy.exchange}")
    private String exchange;

    private final OutboxRepository outboxRepo;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(
            OutboxRepository outboxRepo,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.messaging.outbox-poll-delay:3000}")
    @Transactional
    public void drain() {
        List<OutboxEntity> rows =
                outboxRepo.findByStatusOrderByCreatedAtAsc(
                        "PENDING", PageRequest.of(0, BATCH));

        for (OutboxEntity row : rows) {
            String eventType = row.getEventType();
            String routingKey = routingKeyFor(eventType);
            if (routingKey == null) {
                log.warn(
                        "Unknown eventType={} for outbox id={}, marking FAILED",
                        eventType,
                        row.getId());
                row.setStatus("FAILED");
                outboxRepo.save(row);
                continue;
            }
            try {
                // Build envelope at drain time
                ObjectNode env = objectMapper.createObjectNode();
                env.put("messageId", row.getId().toString());
                env.put("eventType", row.getEventType());
                env.put("eventVersion", "1.0");
                env.put("timestamp", Instant.now().toString()); // ISO-8601 UTC; field name = "timestamp" (B2 contract)
                env.set("payload", objectMapper.readTree(row.getPayload()));

                byte[] body = objectMapper.writeValueAsBytes(env);
                Message msg =
                        MessageBuilder.withBody(body)
                                .setContentType(CONTENT_TYPE_JSON)
                                .setMessageId(row.getId().toString())
                                .build();

                rabbitTemplate.send(exchange, routingKey, msg);

                row.setStatus("PUBLISHED");
                row.setPublishedAt(Instant.now());
                outboxRepo.save(row);

                log.info(
                        "Outbox published: id={} eventType={} rk={}",
                        row.getId(),
                        eventType,
                        routingKey);
            } catch (Exception e) {
                // Keep PENDING — will retry next drain cycle
                log.warn(
                        "Outbox publish failed for id={} eventType={}, keeping PENDING: {}",
                        row.getId(),
                        eventType,
                        e.getMessage());
            }
        }
    }

    private String routingKeyFor(String eventType) {
        return switch (eventType) {
            case "PaymentSucceeded" -> "payment.succeeded";
            case "PaymentFailed" -> "payment.failed";
            default -> null;
        };
    }
}
