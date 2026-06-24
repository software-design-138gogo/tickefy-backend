package com.tickefy.csvingestion.modules.csvimport.repository;

import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VipGuestRepository extends JpaRepository<VipGuestEntity, UUID> {

    List<VipGuestEntity> findByConcertId(UUID concertId);

    /** Internal read endpoint (6a): paginated VIP list of a concert. */
    Page<VipGuestEntity> findByConcertId(UUID concertId, Pageable pageable);

    /** Internal read endpoint (6a): per-email lookup within a concert (email normalized lowercase). */
    Page<VipGuestEntity> findByConcertIdAndEmail(UUID concertId, String email, Pageable pageable);

    boolean existsByConcertIdAndEmail(UUID concertId, String email);

    /**
     * Idempotent promote of a job's staging rows into vip_guests (§6.9). Conflicting
     * (concert_id,email) rows are skipped (already imported). Returns count of NEW rows inserted.
     */
    @Modifying
    @Query(
            value = "INSERT INTO vip_guests "
                    + "(id, concert_id, email, full_name, ticket_type_id, ticket_type_name, import_job_id) "
                    + "SELECT gen_random_uuid(), s.concert_id, s.email, s.full_name, "
                    + "s.ticket_type_id, s.ticket_type_name, s.import_job_id "
                    + "FROM vip_guest_staging s WHERE s.import_job_id = :jobId "
                    + "ON CONFLICT (concert_id, email) DO NOTHING",
            nativeQuery = true)
    int promoteStaging(@Param("jobId") UUID jobId);
}
