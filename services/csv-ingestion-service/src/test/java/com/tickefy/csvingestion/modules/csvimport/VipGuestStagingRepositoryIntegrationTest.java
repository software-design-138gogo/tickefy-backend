package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestStagingRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * AC4 — VipGuestStagingEntity unique constraint (import_job_id, email).
 * §9 retry reset — deleteByImportJobId clears staging rows.
 */
class VipGuestStagingRepositoryIntegrationTest extends BaseRepositoryIntegrationTest {

    @Autowired
    private VipGuestStagingRepository stagingRepository;

    @Autowired
    private ImportJobRepository importJobRepository;

    /** Helper: save a minimal ImportJobEntity and return its id. */
    private UUID saveJob() {
        ImportJobEntity job =
                ImportJobEntity.builder()
                        .concertId(UUID.randomUUID())
                        .organizerId(UUID.randomUUID())
                        .objectKey("uploads/test.csv")
                        .build();
        return importJobRepository.saveAndFlush(job).getId();
    }

    // ===== AC4: duplicate (importJobId, email) throws DataIntegrityViolationException =====

    @Test
    void duplicateJobEmail_throws() {
        UUID jobId = saveJob();
        String email = "dup@example.com";
        UUID concertId = UUID.randomUUID();

        stagingRepository.saveAndFlush(
                VipGuestStagingEntity.builder()
                        .importJobId(jobId)
                        .concertId(concertId)
                        .email(email)
                        .lineNumber(1)
                        .build());

        VipGuestStagingEntity duplicate =
                VipGuestStagingEntity.builder()
                        .importJobId(jobId)
                        .concertId(concertId)
                        .email(email)
                        .lineNumber(2)
                        .build();

        assertThatThrownBy(() -> stagingRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ===== §9 retry reset: deleteByImportJobId removes all staging rows for that job =====

    @Test
    void deleteByImportJobId() {
        UUID jobId = saveJob();
        UUID concertId = UUID.randomUUID();
        int N = 5;

        for (int i = 1; i <= N; i++) {
            stagingRepository.saveAndFlush(
                    VipGuestStagingEntity.builder()
                            .importJobId(jobId)
                            .concertId(concertId)
                            .email("guest" + i + "@example.com")
                            .lineNumber(i)
                            .build());
        }

        assertThat(stagingRepository.findByImportJobId(jobId)).hasSize(N);

        stagingRepository.deleteByImportJobId(jobId);
        stagingRepository.flush();

        assertThat(stagingRepository.findByImportJobId(jobId)).isEmpty();
    }
}
