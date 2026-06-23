package com.tickefy.event.modules.concert;

import com.tickefy.event.modules.artist.Artist;
import com.tickefy.event.modules.venue.Venue;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "concerts")
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "ai_introduction", columnDefinition = "TEXT")
    private String aiIntroduction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConcertStatus status = ConcertStatus.DRAFT;

    @Column(name = "sale_start_at")
    private Instant saleStartAt;

    @Column(name = "sale_end_at")
    private Instant saleEndAt;

    @Column(name = "event_date", nullable = false)
    private Instant eventDate;

    @Column(name = "created_by")
    private UUID createdBy;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "concert_artists",
        joinColumns = @JoinColumn(name = "concert_id"),
        inverseJoinColumns = @JoinColumn(name = "artist_id")
    )
    private Set<Artist> artists = new HashSet<>();

    @OneToMany(mappedBy = "concert", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ConcertZone> zones = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getAiIntroduction() { return aiIntroduction; }
    public Venue getVenue() { return venue; }
    public ConcertStatus getStatus() { return status; }
    public Instant getSaleStartAt() { return saleStartAt; }
    public Instant getSaleEndAt() { return saleEndAt; }
    public Instant getEventDate() { return eventDate; }
    public UUID getCreatedBy() { return createdBy; }
    public Set<Artist> getArtists() { return artists; }
    public Set<ConcertZone> getZones() { return zones; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // Setters
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setAiIntroduction(String aiIntroduction) { this.aiIntroduction = aiIntroduction; }
    public void setVenue(Venue venue) { this.venue = venue; }
    public void setStatus(ConcertStatus status) { this.status = status; }
    public void setSaleStartAt(Instant saleStartAt) { this.saleStartAt = saleStartAt; }
    public void setSaleEndAt(Instant saleEndAt) { this.saleEndAt = saleEndAt; }
    public void setEventDate(Instant eventDate) { this.eventDate = eventDate; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public void setArtists(Set<Artist> artists) { this.artists = artists; }
}
