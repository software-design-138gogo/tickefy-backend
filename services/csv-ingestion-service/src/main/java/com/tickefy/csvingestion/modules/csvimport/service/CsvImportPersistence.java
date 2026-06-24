package com.tickefy.csvingestion.modules.csvimport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.OutboxEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.event.EventEnvelope;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportErrorRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.OutboxRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short DB transactions for import jobs, isolated in a separate bean so that HTTP / object-storage
 * calls stay OUTSIDE the transaction (CLAUDE §8 no-HTTP-in-TX) and run through the Spring proxy.
 */
@Service
public class CsvImportPersistence {

    private final ImportJobRepository importJobRepository;
    private final VipGuestStagingRepository stagingRepository;
    private final ImportErrorRepository errorRepository;
    private final VipGuestRepository vipGuestRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public CsvImportPersistence(
            ImportJobRepository importJobRepository,
            VipGuestStagingRepository stagingRepository,
            ImportErrorRepository errorRepository,
            VipGuestRepository vipGuestRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.importJobRepository = importJobRepository;
        this.stagingRepository = stagingRepository;
        this.errorRepository = errorRepository;
        this.vipGuestRepository = vipGuestRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /** Idempotent promote staging -> vip_guests (ON CONFLICT DO NOTHING). Returns new rows count. */
    @Transactional
    public int promote(UUID jobId) {
        return vipGuestRepository.promoteStaging(jobId);
    }

    /**
     * Atomic terminal transition (only from PROCESSING) + outbox event in the SAME TX (§6.9).
     * Returns true if this caller finalized; outbox is written only when affected==1 (no double-event).
     */
    @Transactional
    public boolean markTerminal(
            UUID jobId, String status, int successRows, String reportKey, EventEnvelope<?> event) {
        boolean applied =
                importJobRepository.markTerminal(jobId, status, successRows, reportKey, Instant.now()) == 1;
        if (applied) {
            writeOutbox(jobId, event);
        }
        return applied;
    }

    /** Atomic failure transition (only from PROCESSING) + outbox event in the SAME TX. */
    @Transactional
    public boolean markFailed(UUID jobId, String reason, EventEnvelope<?> event) {
        boolean applied = importJobRepository.markFailed(jobId, reason, Instant.now()) == 1;
        if (applied) {
            writeOutbox(jobId, event);
        }
        return applied;
    }

    /** Serialize the envelope and append a PENDING outbox row (publisher = T5b). */
    private void writeOutbox(UUID aggregateId, EventEnvelope<?> event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new ApiException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to serialize outbox event " + event.eventType(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        outboxRepository.save(OutboxEntity.builder()
                .aggregateId(aggregateId)
                .eventType(event.eventType())
                .payload(json)
                .status("PENDING")
                .build());
    }

    /** Atomic PENDING -> PROCESSING claim (§6.9). Returns true if this caller claimed the job. */
    @Transactional
    public boolean claimJob(UUID jobId) {
        return importJobRepository.claim(jobId, Instant.now()) == 1;
    }

    /** Persist one batch of staging rows (short TX, mid-parse — §8). */
    @Transactional
    public void saveStagingBatch(List<VipGuestStagingEntity> rows) {
        if (!rows.isEmpty()) {
            stagingRepository.saveAll(rows);
        }
    }

    /** Persist one batch of import-error rows (short TX). */
    @Transactional
    public void saveErrorsBatch(List<ImportErrorEntity> rows) {
        if (!rows.isEmpty()) {
            errorRepository.saveAll(rows);
        }
    }

    /** Update counters on a PROCESSING job (no terminal transition here — that is T4c). */
    @Transactional
    public void updateCounters(UUID jobId, int total, int success, int failed, int duplicate) {
        ImportJobEntity job = importJobRepository.findById(jobId).orElseThrow();
        job.setTotalRows(total);
        job.setSuccessRows(success);
        job.setFailedRows(failed);
        job.setDuplicateRows(duplicate);
        importJobRepository.save(job);
    }

    @Transactional
    public UUID createJob(UUID concertId, UUID organizerId, String objectKey) {
        ImportJobEntity job = ImportJobEntity.builder()
                .concertId(concertId)
                .organizerId(organizerId)
                .source("UPLOAD")
                .objectKey(objectKey)
                .status("PENDING")
                .build();
        return importJobRepository.save(job).getId();
    }

    /** Reset a FAILED job for retry: clear stale staging/errors, reset job to a fresh PENDING state. */
    @Transactional
    public void resetForRetry(UUID jobId) {
        stagingRepository.deleteByImportJobId(jobId);
        errorRepository.deleteByImportJobId(jobId); // clear stale errors so retry's report is clean
        ImportJobEntity job = importJobRepository
                .findById(jobId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.IMPORT_JOB_NOT_FOUND, "Import job not found", HttpStatus.NOT_FOUND));
        job.setStatus("PENDING");
        job.setFailureReason(null);
        // Fully reset to a fresh PENDING state — a retried job must not carry the previous run's
        // terminal timestamps/counters/report (else terminal-detection sees stale finishedAt).
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setErrorReportObjectKey(null);
        job.setTotalRows(0);
        job.setSuccessRows(0);
        job.setFailedRows(0);
        job.setDuplicateRows(0);
        importJobRepository.save(job);
    }
}
