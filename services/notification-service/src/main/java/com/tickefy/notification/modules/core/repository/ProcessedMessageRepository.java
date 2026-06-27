package com.tickefy.notification.modules.core.repository;

import com.tickefy.notification.modules.core.entity.ProcessedMessage;
import com.tickefy.notification.modules.core.entity.ProcessedMessageId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Dedup ledger repository (F1). Idempotency is enforced by the composite PK
 * {@code (messageId, eventType)} via an atomic upsert.
 */
@Repository
public interface ProcessedMessageRepository
        extends JpaRepository<ProcessedMessage, ProcessedMessageId> {

    /**
     * Atomically claims a {@code (messageId, eventType)} pair. Returns {@code 1} when this is the
     * first time the message is seen (row inserted), {@code 0} when it was already processed
     * (conflict, no row inserted) — the caller then skips.
     *
     * <p>Uses {@code INSERT ... ON CONFLICT DO NOTHING} so a duplicate is a no-op (no exception, no
     * transaction poisoning, race-safe). {@code {h-schema}} resolves to the Hibernate
     * {@code default_schema} so the native statement targets the service schema (avoids the
     * native-query search_path pitfall).
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    "INSERT INTO {h-schema}processed_messages (message_id, event_type) "
                            + "VALUES (:messageId, :eventType) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int tryMarkProcessed(@Param("messageId") UUID messageId, @Param("eventType") String eventType);
}
