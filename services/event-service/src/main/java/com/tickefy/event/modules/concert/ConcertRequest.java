package com.tickefy.event.modules.concert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ConcertRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    private String concertIntroduction;

    @NotNull(message = "Venue ID is required")
    private UUID venueId;

    @NotNull(message = "Event date is required")
    private Instant eventDate;

    private Instant saleStartAt;
    private Instant saleEndAt;

    private List<UUID> artistIds;

    @Valid
    private List<ZoneRequest> zones;

    // DTO for concert zones
    public static class ZoneRequest {
        @NotBlank(message = "Ticket type name is required")
        private String ticketTypeName;  // SVIP, VIP, CAT1, CAT2, GA

        @NotBlank(message = "Zone name is required")
        private String zoneName;

        private String svgElementId;
        private String seatMapUrl;

        // Getters & Setters
        public String getTicketTypeName() { return ticketTypeName; }
        public void setTicketTypeName(String ticketTypeName) { this.ticketTypeName = ticketTypeName; }
        public String getZoneName() { return zoneName; }
        public void setZoneName(String zoneName) { this.zoneName = zoneName; }
        public String getSvgElementId() { return svgElementId; }
        public void setSvgElementId(String svgElementId) { this.svgElementId = svgElementId; }
        public String getSeatMapUrl() { return seatMapUrl; }
        public void setSeatMapUrl(String seatMapUrl) { this.seatMapUrl = seatMapUrl; }
    }

    // Getters & Setters
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getConcertIntroduction() { return concertIntroduction; }
    public void setConcertIntroduction(String concertIntroduction) { this.concertIntroduction = concertIntroduction; }
    public UUID getVenueId() { return venueId; }
    public void setVenueId(UUID venueId) { this.venueId = venueId; }
    public Instant getEventDate() { return eventDate; }
    public void setEventDate(Instant eventDate) { this.eventDate = eventDate; }
    public Instant getSaleStartAt() { return saleStartAt; }
    public void setSaleStartAt(Instant saleStartAt) { this.saleStartAt = saleStartAt; }
    public Instant getSaleEndAt() { return saleEndAt; }
    public void setSaleEndAt(Instant saleEndAt) { this.saleEndAt = saleEndAt; }
    public List<UUID> getArtistIds() { return artistIds; }
    public void setArtistIds(List<UUID> artistIds) { this.artistIds = artistIds; }
    public List<ZoneRequest> getZones() { return zones; }
    public void setZones(List<ZoneRequest> zones) { this.zones = zones; }
}
