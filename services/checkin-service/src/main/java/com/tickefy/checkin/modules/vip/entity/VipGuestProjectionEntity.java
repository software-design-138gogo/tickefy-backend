package com.tickefy.checkin.modules.vip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "vip_guest_projection",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_vip_projection_concert_email",
                columnNames = {"concert_id", "email"}))
public class VipGuestProjectionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "concert_id", nullable = false)
    private UUID concertId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "ticket_type_id")
    private UUID ticketTypeId;

    @Column(name = "ticket_type_name", length = 100)
    private String ticketTypeName;

    @Column(name = "source_message_id")
    private UUID sourceMessageId;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    @PrePersist
    protected void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (cachedAt == null) {
            cachedAt = Instant.now();
        }
    }

    public VipGuestProjectionEntity() {}

    public VipGuestProjectionEntity(UUID id, UUID concertId, String email, String fullName,
            UUID ticketTypeId, String ticketTypeName, UUID sourceMessageId, Instant cachedAt) {
        this.id = id;
        this.concertId = concertId;
        this.email = email;
        this.fullName = fullName;
        this.ticketTypeId = ticketTypeId;
        this.ticketTypeName = ticketTypeName;
        this.sourceMessageId = sourceMessageId;
        this.cachedAt = cachedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConcertId() { return concertId; }
    public void setConcertId(UUID concertId) { this.concertId = concertId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public UUID getTicketTypeId() { return ticketTypeId; }
    public void setTicketTypeId(UUID ticketTypeId) { this.ticketTypeId = ticketTypeId; }

    public String getTicketTypeName() { return ticketTypeName; }
    public void setTicketTypeName(String ticketTypeName) { this.ticketTypeName = ticketTypeName; }

    public UUID getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(UUID sourceMessageId) { this.sourceMessageId = sourceMessageId; }

    public Instant getCachedAt() { return cachedAt; }
    public void setCachedAt(Instant cachedAt) { this.cachedAt = cachedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private UUID concertId;
        private String email;
        private String fullName;
        private UUID ticketTypeId;
        private String ticketTypeName;
        private UUID sourceMessageId;
        private Instant cachedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder concertId(UUID concertId) { this.concertId = concertId; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder fullName(String fullName) { this.fullName = fullName; return this; }
        public Builder ticketTypeId(UUID ticketTypeId) { this.ticketTypeId = ticketTypeId; return this; }
        public Builder ticketTypeName(String ticketTypeName) { this.ticketTypeName = ticketTypeName; return this; }
        public Builder sourceMessageId(UUID sourceMessageId) { this.sourceMessageId = sourceMessageId; return this; }
        public Builder cachedAt(Instant cachedAt) { this.cachedAt = cachedAt; return this; }

        public VipGuestProjectionEntity build() {
            return new VipGuestProjectionEntity(id, concertId, email, fullName,
                    ticketTypeId, ticketTypeName, sourceMessageId, cachedAt);
        }
    }
}
