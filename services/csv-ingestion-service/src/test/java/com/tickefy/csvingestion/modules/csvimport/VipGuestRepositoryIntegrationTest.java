package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * AC5 / §6.9 — VipGuestEntity unique constraint (concert_id, email).
 * AC: existsByConcertIdAndEmail query correctness.
 */
class VipGuestRepositoryIntegrationTest extends BaseRepositoryIntegrationTest {

    @Autowired
    private VipGuestRepository vipGuestRepository;

    // ===== AC5/§6.9: duplicate (concertId, email) throws DataIntegrityViolationException =====

    @Test
    void duplicateConcertEmail_throws() {
        UUID concertId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();
        String email = "dup@example.com";

        VipGuestEntity first =
                VipGuestEntity.builder()
                        .concertId(concertId)
                        .email(email)
                        .fullName("First Guest")
                        .ticketTypeId(ticketTypeId)
                        .build();
        vipGuestRepository.saveAndFlush(first);

        VipGuestEntity duplicate =
                VipGuestEntity.builder()
                        .concertId(concertId)
                        .email(email)
                        .fullName("Duplicate Guest")
                        .ticketTypeId(ticketTypeId)
                        .build();

        assertThatThrownBy(() -> vipGuestRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ===== existsByConcertIdAndEmail: true after insert, false for different email/concert =====

    @Test
    void existsByConcertIdAndEmail() {
        UUID concertId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();
        String email = "guest@example.com";

        vipGuestRepository.saveAndFlush(
                VipGuestEntity.builder()
                        .concertId(concertId)
                        .email(email)
                        .ticketTypeId(ticketTypeId)
                        .build());

        assertThat(vipGuestRepository.existsByConcertIdAndEmail(concertId, email)).isTrue();
        assertThat(vipGuestRepository.existsByConcertIdAndEmail(concertId, "other@example.com"))
                .isFalse();
        assertThat(vipGuestRepository.existsByConcertIdAndEmail(UUID.randomUUID(), email))
                .isFalse();
    }
}
