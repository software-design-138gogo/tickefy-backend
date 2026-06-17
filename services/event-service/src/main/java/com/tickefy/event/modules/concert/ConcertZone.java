package com.tickefy.event.modules.concert;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "concert_zones")
public class ConcertZone {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    // Synced with inventory-service ticket_types.name (SVIP, VIP, CAT1, CAT2, GA)
    @Column(name = "ticket_type_name", nullable = false, length = 50)
    private String ticketTypeName;

    @Column(name = "zone_name", nullable = false, length = 100)
    private String zoneName;

    // SVG element id for frontend click-to-select mapping
    @Column(name = "svg_element_id", length = 100)
    private String svgElementId;

    // URL to SVG file stored in MinIO / Object Storage
    @Column(name = "seat_map_url", length = 500)
    private String seatMapUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public Concert getConcert() { return concert; }
    public String getTicketTypeName() { return ticketTypeName; }
    public String getZoneName() { return zoneName; }
    public String getSvgElementId() { return svgElementId; }
    public String getSeatMapUrl() { return seatMapUrl; }
    public Instant getCreatedAt() { return createdAt; }

    // Setters
    public void setConcert(Concert concert) { this.concert = concert; }
    public void setTicketTypeName(String ticketTypeName) { this.ticketTypeName = ticketTypeName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public void setSvgElementId(String svgElementId) { this.svgElementId = svgElementId; }
    public void setSeatMapUrl(String seatMapUrl) { this.seatMapUrl = seatMapUrl; }
}
