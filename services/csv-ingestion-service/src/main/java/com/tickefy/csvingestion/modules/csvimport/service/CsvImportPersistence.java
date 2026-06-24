package com.tickefy.csvingestion.modules.csvimport.service;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
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

    public CsvImportPersistence(
            ImportJobRepository importJobRepository, VipGuestStagingRepository stagingRepository) {
        this.importJobRepository = importJobRepository;
        this.stagingRepository = stagingRepository;
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
