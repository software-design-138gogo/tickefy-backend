package com.tickefy.event.modules.concert;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConcertRepository extends JpaRepository<Concert, UUID> {

    Page<Concert> findByStatus(ConcertStatus status, Pageable pageable);

    Page<Concert> findByStatusIn(List<ConcertStatus> statuses, Pageable pageable);

    @Query("SELECT c FROM Concert c LEFT JOIN FETCH c.venue WHERE c.id = :id")
    java.util.Optional<Concert> findByIdWithVenue(@Param("id") UUID id);

    @Query(
        "SELECT DISTINCT c FROM Concert c LEFT JOIN FETCH c.venue LEFT JOIN FETCH c.artists LEFT JOIN FETCH c.zones WHERE c.id = :id"
    )
    java.util.Optional<Concert> findByIdWithDetails(@Param("id") UUID id);
}
