package com.tickefy.checkin.modules.vip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vip_cache_meta")
public class VipCacheMetaEntity {

    @Id
    @Column(name = "concert_id", nullable = false, updatable = false)
    private UUID concertId;

    @Column(name = "last_refreshed_at", nullable = false)
    private Instant lastRefreshedAt;

    @Column(name = "state", nullable = false, length = 16)
    private String state = "FRESH";

    public VipCacheMetaEntity() {}

    public VipCacheMetaEntity(UUID concertId, Instant lastRefreshedAt, String state) {
        this.concertId = concertId;
        this.lastRefreshedAt = lastRefreshedAt;
        this.state = state;
    }

    public UUID getConcertId() { return concertId; }
    public void setConcertId(UUID concertId) { this.concertId = concertId; }

    public Instant getLastRefreshedAt() { return lastRefreshedAt; }
    public void setLastRefreshedAt(Instant lastRefreshedAt) { this.lastRefreshedAt = lastRefreshedAt; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
