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
import java.util.Objects;
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
    private final ConcertCacheService concertCacheService;

    public ConcertService(
            ConcertRepository concertRepository,
            VenueRepository venueRepository,
            ArtistRepository artistRepository,
            OutboxEventRepository outboxEventRepository,
            ProcessedMessageRepository processedMessageRepository,
            ObjectMapper objectMapper,
            ConcertCacheService concertCacheService) {
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.artistRepository = artistRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.objectMapper = objectMapper;
        this.concertCacheService = concertCacheService;
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
        ConcertResponse cached = concertCacheService.getConcertById(id);
        if (cached != null) {
            return cached;
        }
        throw new ApiException(
            ErrorCode.RESOURCE_NOT_FOUND,
            "Concert not found: " + id,
            HttpStatus.NOT_FOUND);
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
        if (request.getConcertIntroduction() != null) {
            concert.setConcertIntroduction(request.getConcertIntroduction());
            Instant now = Instant.now();
            concert.setConcertIntroductionUpdatedAt(now);
            concert.setManualIntroductionUpdatedAt(now);
        }
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
        concertCacheService.evict(saved.getId());
        concertCacheService.evictList();
        return ConcertResponse.from(saved);
    }

    // --- UPDATE ---
    public ConcertResponse updateConcert(
            UUID id, ConcertRequest request, UUID actorId, boolean admin) {
        Concert concert = concertRepository.findByIdWithDetails(id)
            .orElseThrow(() -> new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Concert not found: " + id,
                HttpStatus.NOT_FOUND));
        requireManageAccess(concert, actorId, admin);

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
        if (request.getConcertIntroduction() != null
                && !Objects.equals(
                        request.getConcertIntroduction(), concert.getConcertIntroduction())) {
            concert.setConcertIntroduction(request.getConcertIntroduction());
            concert.setConcertIntroductionSourceJobId(null);
            concert.setConcertIntroductionLanguage(null);
            concert.setConcertIntroductionUpdatedAt(Instant.now());
            concert.setManualIntroductionUpdatedAt(Instant.now());
        }
        concert.setVenue(venue);
        concert.setEventDate(request.getEventDate());
        concert.setSaleStartAt(request.getSaleStartAt());
        concert.setSaleEndAt(request.getSaleEndAt());

        Concert saved = concertRepository.save(concert);
        concertCacheService.evict(id);
        concertCacheService.evictList();
        return ConcertResponse.from(saved);
    }

    // --- PUBLISH ---
    public ConcertResponse publishConcert(UUID id, UUID actorId, boolean admin) {
        Concert concert = findById(id);
        requireManageAccess(concert, actorId, admin);
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
        
        concertCacheService.evict(id);
        concertCacheService.evictList();
        return ConcertResponse.from(saved);
    }

    // --- CANCEL ---
    public ConcertResponse cancelConcert(
            UUID id, String reason, UUID actorId, boolean admin) {
        Concert concert = findById(id);
        requireManageAccess(concert, actorId, admin);
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
        
        concertCacheService.evict(id);
        concertCacheService.evictList();
        return ConcertResponse.from(saved);
    }

    // --- UPDATE AI INTRODUCTION ---
    public InternalConcertSummaryResponse getInternalConcert(UUID id) {
        return InternalConcertSummaryResponse.from(findById(id));
    }

    public AiConcertContextResponse getAiContext(UUID id) {
        return AiConcertContextResponse.from(findById(id));
    }

    public void requireManageAccess(UUID id, UUID actorId, boolean admin) {
        requireManageAccess(findById(id), actorId, admin);
    }

    public boolean updateAiIntroduction(
            UUID id,
            String introduction,
            String messageId,
            UUID jobId,
            String language,
            Instant requestedAt,
            Instant generatedAt) {
        if (processedMessageRepository.existsById(messageId)) {
            return false;
        }

        Concert concert =
                concertRepository.findByIdForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.CONCERT_NOT_FOUND,
                                                "Concert not found: " + id,
                                                HttpStatus.NOT_FOUND));

        if (processedMessageRepository.existsById(messageId)) {
            return false;
        }

        if (jobId.equals(concert.getConcertIntroductionSourceJobId())) {
            processedMessageRepository.save(
                    new ProcessedMessage(
                            messageId, "ConcertIntroductionGenerated", Instant.now()));
            return false;
        }

        if (concert.getManualIntroductionUpdatedAt() != null
                && concert.getManualIntroductionUpdatedAt().isAfter(requestedAt)) {
            processedMessageRepository.save(
                    new ProcessedMessage(
                            messageId, "ConcertIntroductionGenerated", Instant.now()));
            return false;
        }

        concert.setConcertIntroduction(introduction);
        concert.setConcertIntroductionSourceJobId(jobId);
        concert.setConcertIntroductionLanguage(language);
        concert.setConcertIntroductionUpdatedAt(
                generatedAt != null ? generatedAt : Instant.now());
        concertRepository.save(concert);

        processedMessageRepository.save(
                new ProcessedMessage(
                        messageId, "ConcertIntroductionGenerated", Instant.now()));
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

    private void requireManageAccess(Concert concert, UUID actorId, boolean admin) {
        if (admin) {
            return;
        }
        if (actorId == null || !actorId.equals(concert.getCreatedBy())) {
            throw new ApiException(
                    ErrorCode.CONCERT_ACCESS_DENIED,
                    "You do not have permission to manage this concert.",
                    HttpStatus.FORBIDDEN,
                    Map.of("concertId", concert.getId().toString()));
        }
    }
}
