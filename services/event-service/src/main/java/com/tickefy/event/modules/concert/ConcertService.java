package com.tickefy.event.modules.concert;

import com.tickefy.event.common.exception.ApiException;
import com.tickefy.event.common.exception.ErrorCode;
import com.tickefy.event.modules.artist.Artist;
import com.tickefy.event.modules.artist.ArtistRepository;
import com.tickefy.event.modules.venue.Venue;
import com.tickefy.event.modules.venue.VenueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.modules.outbox.OutboxEvent;
import com.tickefy.event.modules.outbox.OutboxEventRepository;
import com.tickefy.event.modules.outbox.ProcessedMessage;
import com.tickefy.event.modules.outbox.ProcessedMessageRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final ArtistRepository artistRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final ObjectMapper objectMapper;

    public ConcertService(
            ConcertRepository concertRepository,
            VenueRepository venueRepository,
            ArtistRepository artistRepository,
            OutboxEventRepository outboxEventRepository,
            ProcessedMessageRepository processedMessageRepository,
            ObjectMapper objectMapper) {
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.artistRepository = artistRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.objectMapper = objectMapper;
    }

    // --- LIST ---
    @Transactional(readOnly = true)
    public Page<ConcertResponse> listConcerts(ConcertStatus status, Pageable pageable) {
        Page<Concert> page;
        if (status != null) {
            page = concertRepository.findByStatus(status, pageable);
        } else {
            // Default: show only PUBLISHED for public
            page = concertRepository.findByStatusIn(
                List.of(ConcertStatus.PUBLISHED, ConcertStatus.COMPLETED), pageable);
        }
        return page.map(ConcertResponse::summary);
    }

    // --- GET DETAIL ---
    @Transactional(readOnly = true)
    public ConcertResponse getConcertById(UUID id) {
        Concert concert = concertRepository.findByIdWithDetails(id)
            .orElseThrow(() -> new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Concert not found: " + id,
                HttpStatus.NOT_FOUND));
        return ConcertResponse.from(concert);
    }

    // --- CREATE ---
    public ConcertResponse createConcert(ConcertRequest request, UUID createdBy) {
        Venue venue = venueRepository.findById(request.getVenueId())
            .orElseThrow(() -> new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Venue not found: " + request.getVenueId(),
                HttpStatus.NOT_FOUND));

        Concert concert = new Concert();
        concert.setTitle(request.getTitle());
        concert.setDescription(request.getDescription());
        concert.setVenue(venue);
        concert.setEventDate(request.getEventDate());
        concert.setSaleStartAt(request.getSaleStartAt());
        concert.setSaleEndAt(request.getSaleEndAt());
        concert.setCreatedBy(createdBy);

        // Assign artists
        if (request.getArtistIds() != null && !request.getArtistIds().isEmpty()) {
            Set<Artist> artists = new HashSet<>(artistRepository.findAllById(request.getArtistIds()));
            concert.setArtists(artists);
        }

        // Add zones
        if (request.getZones() != null) {
            for (ConcertRequest.ZoneRequest zr : request.getZones()) {
                ConcertZone zone = new ConcertZone();
                zone.setConcert(concert);
                zone.setTicketTypeName(zr.getTicketTypeName());
                zone.setZoneName(zr.getZoneName());
                zone.setSvgElementId(zr.getSvgElementId());
                zone.setSeatMapUrl(zr.getSeatMapUrl());
                concert.getZones().add(zone);
            }
        }

        Concert saved = concertRepository.save(concert);
        return ConcertResponse.from(saved);
    }

    // --- UPDATE ---
    public ConcertResponse updateConcert(UUID id, ConcertRequest request) {
        Concert concert = concertRepository.findByIdWithDetails(id)
            .orElseThrow(() -> new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Concert not found: " + id,
                HttpStatus.NOT_FOUND));

        if (concert.getStatus() == ConcertStatus.CANCELLED
                || concert.getStatus() == ConcertStatus.COMPLETED) {
            throw new ApiException(
                ErrorCode.CONFLICT,
                "Cannot update a " + concert.getStatus() + " concert",
                HttpStatus.CONFLICT);
        }

        Venue venue = venueRepository.findById(request.getVenueId())
            .orElseThrow(() -> new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Venue not found: " + request.getVenueId(),
                HttpStatus.NOT_FOUND));

        concert.setTitle(request.getTitle());
        concert.setDescription(request.getDescription());
        concert.setVenue(venue);
        concert.setEventDate(request.getEventDate());
        concert.setSaleStartAt(request.getSaleStartAt());
        concert.setSaleEndAt(request.getSaleEndAt());

        Concert saved = concertRepository.save(concert);
        return ConcertResponse.from(saved);
    }

    // --- PUBLISH ---
    public ConcertResponse publishConcert(UUID id) {
        Concert concert = findById(id);
        if (concert.getStatus() != ConcertStatus.DRAFT) {
            throw new ApiException(
                ErrorCode.CONFLICT,
                "Only DRAFT concerts can be published. Current status: " + concert.getStatus(),
                HttpStatus.CONFLICT);
        }
        concert.setStatus(ConcertStatus.PUBLISHED);
        Concert saved = concertRepository.save(concert);
        
        try {
            OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(saved.getId().toString())
                .eventType("ConcertPublished")
                .payload(objectMapper.writeValueAsString(ConcertResponse.from(saved)))
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outbox event", e);
        }
        
        return ConcertResponse.from(saved);
    }

    // --- CANCEL ---
    public ConcertResponse cancelConcert(UUID id, String reason) {
        Concert concert = findById(id);
        if (concert.getStatus() == ConcertStatus.CANCELLED
                || concert.getStatus() == ConcertStatus.COMPLETED) {
            throw new ApiException(
                ErrorCode.CONFLICT,
                "Cannot cancel a " + concert.getStatus() + " concert",
                HttpStatus.CONFLICT);
        }
        concert.setStatus(ConcertStatus.CANCELLED);
        Concert saved = concertRepository.save(concert);
        
        try {
            OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(saved.getId().toString())
                .eventType("ConcertCancelled")
                .payload(objectMapper.writeValueAsString(Map.of("reason", reason != null ? reason : "")))
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outbox event", e);
        }
        
        return ConcertResponse.from(saved);
    }

    // --- UPDATE AI INTRODUCTION ---
    public boolean updateAiIntroduction(UUID id, String aiIntroduction, String messageId, Instant requestedAt) {
        if (processedMessageRepository.existsById(messageId)) {
            return false; // Idempotent
        }

        Concert concert = findById(id);

        // Staleness guard: if manual update is newer than the AI request, skip
        if (concert.getAiIntroductionUpdatedAt() != null && requestedAt != null) {
            if (concert.getAiIntroductionUpdatedAt().isAfter(requestedAt)) {
                processedMessageRepository.save(new ProcessedMessage(messageId, "ConcertIntroductionGenerated", Instant.now()));
                return false;
            }
        }

        concert.setAiIntroduction(aiIntroduction);
        
        // Use requestedAt as the source of truth for when this update was generated, 
        // to prevent processing delays from artificially bumping the timestamp.
        concert.setAiIntroductionUpdatedAt(requestedAt != null ? requestedAt : Instant.now());
        
        concertRepository.save(concert);

        processedMessageRepository.save(new ProcessedMessage(messageId, "ConcertIntroductionGenerated", Instant.now()));
        return true;
    }

    // --- HELPER ---
    private Concert findById(UUID id) {
        return concertRepository.findById(id)
            .orElseThrow(() -> new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Concert not found: " + id,
                HttpStatus.NOT_FOUND));
    }
}
