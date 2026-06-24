package com.tickefy.csvingestion.modules.csvimport.service;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportErrorRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    public CsvImportPersistence(
            ImportJobRepository importJobRepository,
            VipGuestStagingRepository stagingRepository,
            ImportErrorRepository errorRepository) {
        this.importJobRepository = importJobRepository;
        this.stagingRepository = stagingRepository;
        this.errorRepository = errorRepository;
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

    /** Reset a FAILED job for retry: clear stale staging rows, flip back to PENDING. */
    @Transactional
    public void resetForRetry(UUID jobId) {
        stagingRepository.deleteByImportJobId(jobId);
        ImportJobEntity job = importJobRepository.findById(jobId).orElseThrow();
        job.setStatus("PENDING");
        job.setFailureReason(null);
        importJobRepository.save(job);
    }
}
