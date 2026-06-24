package com.tickefy.csvingestion.modules.csvimport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "import_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportJobEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "concert_id", nullable = false)
    private UUID concertId;

    // nullable: CRON-sourced jobs have no uploader (V3 + chk_import_jobs_organizer); UPLOAD still sets it.
    @Column(name = "organizer_id")
    private UUID organizerId;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "error_report_object_key", length = 512)
    private String errorReportObjectKey;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows;

    @Column(name = "success_rows", nullable = false)
    private Integer successRows;

    @Column(name = "failed_rows", nullable = false)
    private Integer failedRows;

    @Column(name = "duplicate_rows", nullable = false)
    private Integer duplicateRows;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (source == null) {
            source = "UPLOAD";
        }
        if (status == null) {
            status = "PENDING";
        }
        if (totalRows == null) {
            totalRows = 0;
        }
        if (successRows == null) {
            successRows = 0;
        }
        if (failedRows == null) {
            failedRows = 0;
        }
        if (duplicateRows == null) {
            duplicateRows = 0;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
