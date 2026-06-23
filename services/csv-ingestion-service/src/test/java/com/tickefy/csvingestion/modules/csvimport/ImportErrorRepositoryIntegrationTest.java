package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportErrorRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * findByImportJobId returns all errors belonging to a job.
 */
class ImportErrorRepositoryIntegrationTest extends BaseRepositoryIntegrationTest {

    @Autowired
    private ImportErrorRepository importErrorRepository;

    @Autowired
    private ImportJobRepository importJobRepository;

    @Test
    void findByImportJobId() {
        // Create a job to satisfy the FK import_job_id REFERENCES import_jobs(id)
        ImportJobEntity job =
                ImportJobEntity.builder()
                        .concertId(UUID.randomUUID())
                        .organizerId(UUID.randomUUID())
                        .objectKey("uploads/errors.csv")
                        .build();
        UUID jobId = importJobRepository.saveAndFlush(job).getId();

        // Insert 3 errors for this job
        for (int i = 1; i <= 3; i++) {
            importErrorRepository.saveAndFlush(
                    ImportErrorEntity.builder()
                            .importJobId(jobId)
                            .lineNumber(i)
                            .rawData("bad,row," + i)
                            .reason("INVALID_EMAIL")
                            .build());
        }

        // Insert 1 error for a different job (should NOT appear in result)
        ImportJobEntity otherJob =
                ImportJobEntity.builder()
                        .concertId(UUID.randomUUID())
                        .organizerId(UUID.randomUUID())
                        .objectKey("uploads/other.csv")
                        .build();
        UUID otherJobId = importJobRepository.saveAndFlush(otherJob).getId();
        importErrorRepository.saveAndFlush(
                ImportErrorEntity.builder()
                        .importJobId(otherJobId)
                        .lineNumber(99)
                        .reason("DUPLICATE")
                        .build());

        List<ImportErrorEntity> errors = importErrorRepository.findByImportJobId(jobId);

        assertThat(errors).hasSize(3);
        assertThat(errors).allMatch(e -> e.getImportJobId().equals(jobId));
    }
}
