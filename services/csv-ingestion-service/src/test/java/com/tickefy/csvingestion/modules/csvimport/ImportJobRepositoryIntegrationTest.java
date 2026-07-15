package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @PrePersist sets status='PENDING', counters=0, source='UPLOAD' when nulls passed.
 * findById round-trip verifies persisted defaults.
 */
class ImportJobRepositoryIntegrationTest extends BaseRepositoryIntegrationTest {

    @Autowired
    private ImportJobRepository importJobRepository;

    // ===== @PrePersist defaults: status=PENDING, counters=0, source=UPLOAD =====

    @Test
    void saveFindDefaults() {
        // Build with only required NON-NULL fields; leave status/counters/source null
        ImportJobEntity job =
                ImportJobEntity.builder()
                        .concertId(UUID.randomUUID())
                        .organizerId(UUID.randomUUID())
                        .objectKey("uploads/guests.csv")
                        // status, source, totalRows, successRows, failedRows,
                        // duplicateRows, attemptCount intentionally not set
                        .build();

        ImportJobEntity saved = importJobRepository.saveAndFlush(job);
        UUID id = saved.getId();

        assertThat(id).isNotNull();

        ImportJobEntity found = importJobRepository.findById(id).orElseThrow();

        assertThat(found.getStatus()).isEqualTo("PENDING");
        assertThat(found.getSource()).isEqualTo("UPLOAD");
        assertThat(found.getTotalRows()).isEqualTo(0);
        assertThat(found.getSuccessRows()).isEqualTo(0);
        assertThat(found.getFailedRows()).isEqualTo(0);
        assertThat(found.getDuplicateRows()).isEqualTo(0);
        assertThat(found.getAttemptCount()).isEqualTo(0);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getConcertId()).isEqualTo(job.getConcertId());
        assertThat(found.getOrganizerId()).isEqualTo(job.getOrganizerId());
        assertThat(found.getObjectKey()).isEqualTo("uploads/guests.csv");
    }
}
