package com.tickefy.checkin.modules.vip.messaging;

import com.tickefy.checkin.modules.vip.messaging.dto.VipImportEnvelope;
import com.tickefy.checkin.modules.vip.repository.ProcessedMessageRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer for VipGuestImportCompleted (rk vip-guest-import.completed).
 * Action: dedup (processed_messages) → mark vip_cache_meta STALE.
 * KHÔNG pull csv — mark-stale only (no token-context in consumer, §PLAN §G).
 */
@Component
public class VipImportConsumer {

    private static final Logger log = LoggerFactory.getLogger(VipImportConsumer.class);

    private final ProcessedMessageRepository processedMessageRepo;
    private final VipImportConsumerService consumerService;

    public VipImportConsumer(ProcessedMessageRepository processedMessageRepo,
                             VipImportConsumerService consumerService) {
        this.processedMessageRepo = processedMessageRepo;
        this.consumerService = consumerService;
    }

    @RabbitListener(queues = "checkin.vip-guest-import-completed")
    public void onVipImportCompleted(VipImportEnvelope env) {
        if (env == null || env.messageId() == null || env.payload() == null || env.payload().concertId() == null) {
            throw new IllegalArgumentException("VipGuestImportCompleted missing messageId/payload.concertId");
        }

        UUID messageId = UUID.fromString(env.messageId()); // bad UUID → IllegalArgumentException → DLQ
        UUID concertId = env.payload().concertId();

        if (processedMessageRepo.existsById(messageId)) { // §6.9 dedup
            log.info("VipImport dup messageId={} concertId={} skip", messageId, concertId);
            return; // ack + skip
        }

        consumerService.markStaleAndRecord(messageId, env.eventType(), concertId); // TX ngắn §8

        // §15: log CHI metadata UUID — KHÔNG log payload tho/PII
        log.info("VipImport processed messageId={} concertId={} importJobId={}",
                messageId, concertId, env.payload().importJobId());
    }
}
