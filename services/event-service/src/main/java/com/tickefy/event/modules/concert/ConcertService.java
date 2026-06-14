package com.tickefy.event.modules.concert;

import com.tickefy.event.common.exception.ApiException;
import com.tickefy.event.common.exception.ErrorCode;
import com.tickefy.event.modules.artist.Artist;
import com.tickefy.event.modules.artist.ArtistRepository;
import com.tickefy.event.modules.venue.Venue;
import com.tickefy.event.modules.venue.VenueRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    public ConcertService(
            ConcertRepository concertRepository,
            VenueRepository venueRepository,
            ArtistRepository artistRepository) {
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.artistRepository = artistRepository;
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
        // TODO Phase 2: publish ConcertPublished event to RabbitMQ
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
        // TODO Phase 2: publish ConcertCancelled event to RabbitMQ with reason
        return ConcertResponse.from(saved);
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
