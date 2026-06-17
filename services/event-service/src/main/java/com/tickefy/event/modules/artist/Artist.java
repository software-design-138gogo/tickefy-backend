package com.tickefy.event.modules.artist;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "artists")
public class Artist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "bio_generated_at")
    private Instant bioGeneratedAt;

    @Column(name = "press_kit_url", length = 500)
    private String pressKitUrl;

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
    public String getName() { return name; }
    public String getBio() { return bio; }
    public Instant getBioGeneratedAt() { return bioGeneratedAt; }
    public String getPressKitUrl() { return pressKitUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // Setters
    public void setName(String name) { this.name = name; }
    public void setBio(String bio) { this.bio = bio; }
    public void setBioGeneratedAt(Instant bioGeneratedAt) { this.bioGeneratedAt = bioGeneratedAt; }
    public void setPressKitUrl(String pressKitUrl) { this.pressKitUrl = pressKitUrl; }
}
