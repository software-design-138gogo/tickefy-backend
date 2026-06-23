package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.csvingestion.modules.csvimport.entity.OutboxEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * findByStatusOrderByCreatedAtAsc("PENDING", page) returns only PENDING rows.
 */
class OutboxRepositoryIntegrationTest extends BaseRepositoryIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void findPending() {
        UUID aggId1 = UUID.randomUUID();
        UUID aggId2 = UUID.randomUUID();
        UUID aggId3 = UUID.randomUUID();

        // 2 PENDING
        outboxRepository.saveAndFlush(
                OutboxEntity.builder()
                        .aggregateId(aggId1)
                        .eventType("VipGuestImported")
                        .payload("{\"jobId\":\"" + aggId1 + "\"}")
                        .status("PENDING")
                        .build());

        outboxRepository.saveAndFlush(
                OutboxEntity.builder()
                        .aggregateId(aggId2)
                        .eventType("VipGuestImported")
                        .payload("{\"jobId\":\"" + aggId2 + "\"}")
                        .status("PENDING")
                        .build());

        // 1 PUBLISHED — must NOT appear in PENDING result
        outboxRepository.saveAndFlush(
                OutboxEntity.builder()
                        .aggregateId(aggId3)
                        .eventType("VipGuestImported")
                        .payload("{\"jobId\":\"" + aggId3 + "\"}")
                        .status("PUBLISHED")
                        .build());

        List<OutboxEntity> pending =
                outboxRepository.findByStatusOrderByCreatedAtAsc(
                        "PENDING", PageRequest.of(0, 10));

        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(e -> "PENDING".equals(e.getStatus()));
    }
}
