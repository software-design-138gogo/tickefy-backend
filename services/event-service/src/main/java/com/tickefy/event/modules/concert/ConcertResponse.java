package com.tickefy.event.modules.concert;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ConcertResponse {

    private UUID id;
    private String title;
    private String description;
    private String aiIntroduction;
    private ConcertStatus status;
    private Instant eventDate;
    private Instant saleStartAt;
    private Instant saleEndAt;
    private VenueSummary venue;
    private List<ArtistSummary> artists;
    private List<ZoneResponse> ticketTypes;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID organizerId;

    // --- Static factory ---
    public static ConcertResponse from(Concert concert) {
        ConcertResponse r = new ConcertResponse();
        r.id = concert.getId();
        r.title = concert.getTitle();
        r.description = concert.getDescription();
        r.aiIntroduction = concert.getAiIntroduction();
        r.status = concert.getStatus();
        r.eventDate = concert.getEventDate();
        r.saleStartAt = concert.getSaleStartAt();
        r.saleEndAt = concert.getSaleEndAt();
        r.createdAt = concert.getCreatedAt();
        r.updatedAt = concert.getUpdatedAt();
        r.organizerId = concert.getCreatedBy();

        if (concert.getVenue() != null) {
            r.venue = VenueSummary.from(concert.getVenue());
        }
        if (concert.getArtists() != null) {
            r.artists = concert.getArtists().stream()
                .map(ArtistSummary::from)
                .collect(Collectors.toList());
        }
        if (concert.getZones() != null) {
            r.ticketTypes = concert.getZones().stream()
                .map(ZoneResponse::from)
                .collect(Collectors.toList());
        }
        return r;
    }

    // --- Summary for list view (no artists/zones) ---
    public static ConcertResponse summary(Concert concert) {
        ConcertResponse r = new ConcertResponse();
        r.id = concert.getId();
        r.title = concert.getTitle();
        r.status = concert.getStatus();
        r.eventDate = concert.getEventDate();
        r.saleStartAt = concert.getSaleStartAt();
        r.organizerId = concert.getCreatedBy();
        if (concert.getVenue() != null) {
            r.venue = VenueSummary.from(concert.getVenue());
        }
        return r;
    }

    // --- Nested DTOs ---
    public static class VenueSummary {
        private UUID id;
        private String name;
        private String city;

        public static VenueSummary from(com.tickefy.event.modules.venue.Venue v) {
            VenueSummary s = new VenueSummary();
            s.id = v.getId();
            s.name = v.getName();
            s.city = v.getCity();
            return s;
        }
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String getCity() { return city; }
    }

    public static class ArtistSummary {
        private UUID id;
        private String name;
        private String bio;

        public static ArtistSummary from(com.tickefy.event.modules.artist.Artist a) {
            ArtistSummary s = new ArtistSummary();
            s.id = a.getId();
            s.name = a.getName();
            s.bio = a.getBio();
            return s;
        }
        public UUID getId() { return id; }
        public String getName() { return name; }
        public String getBio() { return bio; }
    }

    public static class ZoneResponse {
        private UUID id;
        private String ticketTypeName;
        private String zoneName;
        private String svgElementId;
        private String seatMapUrl;

        public static ZoneResponse from(ConcertZone z) {
            ZoneResponse r = new ZoneResponse();
            r.id = z.getId();
            r.ticketTypeName = z.getTicketTypeName();
            r.zoneName = z.getZoneName();
            r.svgElementId = z.getSvgElementId();
            r.seatMapUrl = z.getSeatMapUrl();
            return r;
        }
        public UUID getId() { return id; }
        public String getTicketTypeName() { return ticketTypeName; }
        public String getZoneName() { return zoneName; }
        public String getSvgElementId() { return svgElementId; }
        public String getSeatMapUrl() { return seatMapUrl; }
    }

    // Getters for main fields
    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getAiIntroduction() { return aiIntroduction; }
    public ConcertStatus getStatus() { return status; }
    public Instant getEventDate() { return eventDate; }
    public Instant getSaleStartAt() { return saleStartAt; }
    public Instant getSaleEndAt() { return saleEndAt; }
    public VenueSummary getVenue() { return venue; }
    public List<ArtistSummary> getArtists() { return artists; }
    public List<ZoneResponse> getTicketTypes() { return ticketTypes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getOrganizerId() { return organizerId; }
}
