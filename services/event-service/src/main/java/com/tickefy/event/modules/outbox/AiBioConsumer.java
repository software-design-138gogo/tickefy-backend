package com.tickefy.event.modules.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.config.RabbitMQConfig;
import com.tickefy.event.modules.concert.ConcertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Slf4j
@Component
public class AiBioConsumer {

    private final ConcertService concertService;
    private final ObjectMapper objectMapper;

    public AiBioConsumer(ConcertService concertService, ObjectMapper objectMapper) {
        this.concertService = concertService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CONCERT_INTRODUCTION)
    public void consumeAiBioGeneratedEvent(Message amqpMessage) {
        String messageStr = new String(amqpMessage.getBody());
        log.info("[AiBioConsumer] Received message from {}: {}", RabbitMQConfig.QUEUE_CONCERT_INTRODUCTION, messageStr);
        
        try {
            // Parse EventEnvelope
            JsonNode rootNode = objectMapper.readTree(messageStr);
            
            // Check eventType to be safe
            String eventType = rootNode.path("eventType").asText();
            if (!"ConcertIntroductionGenerated".equals(eventType)) {
                log.warn("[AiBioConsumer] Received unexpected eventType: {}", eventType);
                return;
            }

            JsonNode payload = rootNode.path("payload");
            if (payload.isMissingNode()) {
                log.error("[AiBioConsumer] Missing payload in event envelope");
                return;
            }

            // Extract concertId and introduction
            String concertIdStr = payload.path("concertId").asText();
            String introduction = payload.path("introduction").asText();

            if (concertIdStr == null || concertIdStr.isBlank() || introduction == null || introduction.isBlank()) {
                log.error("[AiBioConsumer] Missing required fields (concertId or introduction) in payload: {}", payload);
                return;
            }

            UUID concertId = UUID.fromString(concertIdStr);
            
            // Call ConcertService to update
            concertService.updateAiIntroduction(concertId, introduction);
            log.info("[AiBioConsumer] Successfully updated AI introduction for concert: {}", concertId);
            
        } catch (Exception e) {
            log.error("[AiBioConsumer] Failed to process AiBioGeneratedEvent. Message: {}", messageStr, e);
            throw new RuntimeException("Failed to process AiBioGeneratedEvent", e); // Will be retried and sent to DLQ if max-attempts reached
        }
    }
}
