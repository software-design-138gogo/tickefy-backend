package com.tickefy.event.modules.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.modules.concert.ConcertCacheService;
import com.tickefy.event.modules.concert.ConcertService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

class AiBioConsumerTest {

    @Mock private ConcertService concertService;
    @Mock private ConcertCacheService concertCacheService;

    private AiBioConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new AiBioConsumer(concertService, new ObjectMapper(), concertCacheService);
    }

    @Test
    void consumesValidContractEnvelopeAndEvictsCache() {
        UUID concertId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();
        Instant requestedAt = Instant.parse("2026-06-24T01:00:00Z");
        Instant generatedAt = Instant.parse("2026-06-24T01:01:00Z");
        String body =
                """
                {
                  "messageId":"%s",
                  "eventType":"ConcertIntroductionGenerated",
                  "eventVersion":"1.0",
                  "source":"ai-bio-service",
                  "occurredAt":"2026-06-24T01:01:00Z",
                  "correlationId":"req-test",
                  "causationId":null,
                  "payload":{
                    "jobId":"%s",
                    "concertId":"%s",
                    "introduction":"Generated concert introduction.",
                    "language":"en",
                    "sourceDocumentIds":[],
                    "sourceTypes":[],
                    "requestedAt":"%s",
                    "generatedAt":"%s"
                  }
                }
                """
                        .formatted(messageId, jobId, concertId, requestedAt, generatedAt);
        when(concertService.updateAiIntroduction(
                        concertId,
                        "Generated concert introduction.",
                        messageId,
                        jobId,
                        "en",
                        requestedAt,
                        generatedAt))
                .thenReturn(true);

        consumer.consumeAiBioGeneratedEvent(
                new Message(body.getBytes(StandardCharsets.UTF_8)));

        verify(concertService)
                .updateAiIntroduction(
                        concertId,
                        "Generated concert introduction.",
                        messageId,
                        jobId,
                        "en",
                        requestedAt,
                        generatedAt);
        verify(concertCacheService).evict(concertId);
    }

    @Test
    void rejectsUnsupportedEnvelopeVersionWithoutApplyingData() {
        String body =
                """
                {
                  "messageId":"%s",
                  "eventType":"ConcertIntroductionGenerated",
                  "eventVersion":"2.0",
                  "source":"ai-bio-service",
                  "occurredAt":"2026-06-24T01:01:00Z",
                  "correlationId":"req-test",
                  "payload":{}
                }
                """
                        .formatted(UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                consumer.consumeAiBioGeneratedEvent(
                                        new Message(body.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(concertService, concertCacheService);
    }
}
