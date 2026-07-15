package com.tickefy.event.modules.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.config.RabbitMQConfig;
import com.tickefy.event.modules.concert.ConcertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class AiBioConsumer {

    private final ConcertService concertService;
    private final ObjectMapper objectMapper;
    private final com.tickefy.event.modules.concert.ConcertCacheService concertCacheService;

    public AiBioConsumer(ConcertService concertService, ObjectMapper objectMapper, com.tickefy.event.modules.concert.ConcertCacheService concertCacheService) {
        this.concertService = concertService;
        this.objectMapper = objectMapper;
        this.concertCacheService = concertCacheService;
    }

    @RabbitListener(queues = "${app.rabbitmq.concert-introduction-queue}")
    public void consumeAiBioGeneratedEvent(Message amqpMessage) {
        String messageStr = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        
        try {
            JsonNode rootNode = objectMapper.readTree(messageStr);
            String messageId = requiredText(rootNode, "messageId");
            requireValue(rootNode, "eventType", "ConcertIntroductionGenerated");
            requireValue(rootNode, "eventVersion", "1.0");
            requireValue(rootNode, "source", "ai-bio-service");
            requiredText(rootNode, "occurredAt");
            requiredText(rootNode, "correlationId");

            JsonNode payload = rootNode.path("payload");
            if (!payload.isObject()) {
                throw invalidMessage("payload must be an object");
            }

            UUID concertId = parseUuid(requiredText(payload, "concertId"), "concertId");
            UUID jobId = parseUuid(requiredText(payload, "jobId"), "jobId");
            String introduction = requiredText(payload, "introduction");
            String language = requiredText(payload, "language");
            Instant requestedAt = parseInstant(requiredText(payload, "requestedAt"), "requestedAt");
            Instant generatedAt = parseInstant(requiredText(payload, "generatedAt"), "generatedAt");

            log.info(
                    "[AiBioConsumer] Received messageId={} concertId={} jobId={}",
                    messageId,
                    concertId,
                    jobId);

            boolean updated =
                    concertService.updateAiIntroduction(
                            concertId,
                            introduction,
                            messageId,
                            jobId,
                            language,
                            requestedAt,
                            generatedAt);
            if (updated) {
                log.info("[AiBioConsumer] Successfully updated AI introduction for concert: {}", concertId);
                concertCacheService.evict(concertId);
            } else {
                log.info("[AiBioConsumer] Skipped update for concert: {} (Message {} already processed or stale)", concertId, messageId);
            }
            
        } catch (AmqpRejectAndDontRequeueException exception) {
            log.error("[AiBioConsumer] Rejected invalid ConcertIntroductionGenerated: {}", exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            log.error("[AiBioConsumer] Failed to process ConcertIntroductionGenerated", exception);
            throw new RuntimeException("Failed to process ConcertIntroductionGenerated", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw invalidMessage("missing required field: " + field);
        }
        return value;
    }

    private void requireValue(JsonNode node, String field, String expected) {
        String actual = requiredText(node, field);
        if (!expected.equals(actual)) {
            throw invalidMessage(
                    "unsupported " + field + ": " + actual + ", expected: " + expected);
        }
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalidMessage("invalid UUID field: " + field);
        }
    }

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw invalidMessage("invalid timestamp field: " + field);
        }
    }

    private AmqpRejectAndDontRequeueException invalidMessage(String reason) {
        return new AmqpRejectAndDontRequeueException(reason);
    }
}
