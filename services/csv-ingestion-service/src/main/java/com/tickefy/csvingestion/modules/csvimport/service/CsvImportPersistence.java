package com.tickefy.csvingestion.modules.csvimport.service;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
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

    public CsvImportPersistence(ImportJobRepository importJobRepository) {
        this.importJobRepository = importJobRepository;
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
}
