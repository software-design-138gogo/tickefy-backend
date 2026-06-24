package com.tickefy.csvingestion.modules.csvimport.worker;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.event.CsvEvents;
import com.tickefy.csvingestion.modules.csvimport.event.EventEnvelope;
import com.tickefy.csvingestion.modules.csvimport.event.VipGuestImportFailedPayload;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.service.CsvImportPersistence;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Self-heal: marks jobs stuck in PROCESSING past a threshold (worker crash/hang) as FAILED.
 *
 * <p>Reuses {@link CsvImportPersistence#markFailed} — atomic state-guard (WHERE status='PROCESSING',
 * §6.9) + outbox VipGuestImportFailed event. If a job finalizes between the query and markFailed,
 * affected==0 → no-op (no overwrite, no double-event). Does NOT re-trigger; manual /retry remains.
 */
@Component
@ConditionalOnProperty(name = "app.csv.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class StuckImportReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckImportReaper.class);
    private static final int BATCH = 50;
    private static final String STUCK_REASON = "STUCK_TIMEOUT";

    private final ImportJobRepository importJobRepository;
    private final CsvImportPersistence persistence;

    @Value("${app.csv.reaper.stuck-threshold-min:10}")
    private long stuckThresholdMinutes;

    public StuckImportReaper(ImportJobRepository importJobRepository, CsvImportPersistence persistence) {
        this.importJobRepository = importJobRepository;
        this.persistence = persistence;
    }

    @Scheduled(fixedDelayString = "${app.csv.reaper.poll-ms:60000}")
    public void reapStuck() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(stuckThresholdMinutes));
        List<ImportJobEntity> stuck = importJobRepository
                .findByStatusAndStartedAtBefore("PROCESSING", cutoff, PageRequest.of(0, BATCH));
        for (ImportJobEntity job : stuck) {
            EventEnvelope<?> event = EventEnvelope.of(
                    CsvEvents.EventType.VIP_GUEST_IMPORT_FAILED,
                    new VipGuestImportFailedPayload(job.getId(), job.getConcertId(), STUCK_REASON));
            boolean reaped = persistence.markFailed(job.getId(), STUCK_REASON, event); // atomic WHERE PROCESSING
            if (reaped) {
                // §15: id + timestamp only, never PII.
                log.warn("Reaped stuck import job id={} startedAt={} -> FAILED {}",
                        job.getId(), job.getStartedAt(), STUCK_REASON);
            }
            // reaped==false: job finalized concurrently (affected==0) -> no-op (race-safe).
        }
    }
}
