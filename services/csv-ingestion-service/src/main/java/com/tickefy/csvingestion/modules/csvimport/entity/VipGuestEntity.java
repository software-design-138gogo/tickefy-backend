package com.tickefy.csvingestion.modules.csvimport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "vip_guests",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_vip_guests_concert_email",
                        columnNames = {"concert_id", "email"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VipGuestEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "concert_id", nullable = false)
    private UUID concertId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "ticket_type_id", nullable = false)
    private UUID ticketTypeId;

    @Column(name = "ticket_type_name", length = 100)
    private String ticketTypeName;

    @Column(name = "import_job_id")
    private UUID importJobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
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
