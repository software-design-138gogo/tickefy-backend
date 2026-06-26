package com.tickefy.notification.modules.outbox;

import com.tickefy.notification.modules.core.entity.NotificationOutbox;
import com.tickefy.notification.modules.core.repository.NotificationOutboxRepository;
import com.tickefy.notification.modules.outbox.dto.InternalEmailMessage;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tickefy.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationOutboxRelay {

    private final NotificationOutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${RABBITMQ_EXCHANGE:tickefy.events}")
    private String exchangeEvents;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void processOutbox() {
        // Use batch size of 100 to avoid locking too many rows at once
        List<NotificationOutbox> pendingRecords = outboxRepository.findPendingForUpdate(LocalDateTime.now(), PageRequest.of(0, 100));
        
        if (pendingRecords.isEmpty()) {
            return;
        }

        log.info("[OutboxRelay] Processing {} pending notification records...", pendingRecords.size());

        for (NotificationOutbox record : pendingRecords) {
            try {
                if ("EMAIL".equals(record.getChannel())) {
                    InternalEmailMessage message = InternalEmailMessage.builder()
                            .outboxId(record.getId())
                            .recipientEmail(record.getRecipient())
                            .payloadJson(record.getPayload())
                            .build();

                    rabbitTemplate.convertAndSend(exchangeEvents, "internal.email.send", message);
                } else {
                    log.warn("[OutboxRelay] Unsupported channel: {}", record.getChannel());
                }

                // Update status to PROCESSING so it won't be picked up again immediately
                record.setStatus(NotificationOutbox.OutboxStatus.PROCESSING);
                outboxRepository.save(record);
            } catch (Exception e) {
                log.error("[OutboxRelay] Failed to publish record id={}", record.getId(), e);
                // Leave it as PENDING to retry, or set to FAILED if it's an unrecoverable error.
                // We leave it PENDING, but without backoff it might loop. So we increment retry and backoff.
                record.setRetryCount(record.getRetryCount() + 1);
                record.setNextRetryAt(LocalDateTime.now().plusSeconds(10));
                outboxRepository.save(record);
            }
        }
    }
}
