package com.tickefy.event.modules.concert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.modules.artist.ArtistRepository;
import com.tickefy.event.modules.outbox.OutboxEvent;
import com.tickefy.event.modules.outbox.OutboxEventRepository;
import com.tickefy.event.modules.outbox.ProcessedMessageRepository;
import com.tickefy.event.modules.venue.Venue;
import com.tickefy.event.modules.venue.VenueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConcertServiceTest {

    @Mock private ConcertRepository concertRepository;
    @Mock private VenueRepository venueRepository;
    @Mock private ArtistRepository artistRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ProcessedMessageRepository processedMessageRepository;
    @Mock private ConcertCacheService concertCacheService;

    private ObjectMapper objectMapper;
    private ConcertService concertService;

    private UUID userId;
    private UUID concertId;
    private ConcertRequest request;
    private Concert mockConcert;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        concertService = new ConcertService(
                concertRepository,
                venueRepository,
                artistRepository,
                outboxEventRepository,
                processedMessageRepository,
                objectMapper,
                concertCacheService);

        userId = UUID.randomUUID();
        concertId = UUID.randomUUID();

        request = new ConcertRequest();
        request.setTitle("Test Concert");
        request.setEventDate(Instant.now().plusSeconds(86400));
        request.setSaleStartAt(Instant.now());
        request.setSaleEndAt(Instant.now().plusSeconds(3600));
        request.setVenueId(UUID.randomUUID());
        request.setArtistIds(List.of());
        request.setZones(List.of());

        mockConcert = new Concert();
        ReflectionTestUtils.setField(mockConcert, "id", concertId);
        mockConcert.setTitle("Test Concert");
        mockConcert.setCreatedBy(userId);
        mockConcert.setStatus(ConcertStatus.DRAFT);

        Venue mockVenue = new Venue();
        ReflectionTestUtils.setField(mockVenue, "id", request.getVenueId());
        
        when(concertRepository.save(any(Concert.class))).thenReturn(mockConcert);
    }

    @Test
    void createConcert_ShouldSaveAndEvictCache() {
        Venue mockVenue = new Venue();
        ReflectionTestUtils.setField(mockVenue, "id", request.getVenueId());
        when(venueRepository.findById(request.getVenueId())).thenReturn(Optional.of(mockVenue));

        ConcertResponse response = concertService.createConcert(request, userId);

        assertThat(response.getId()).isEqualTo(concertId);
        verify(concertRepository).save(any(Concert.class));
        verify(concertCacheService).evict(concertId);
        verify(concertCacheService).evictList();
    }

    @Test
    void updateConcert_ShouldSaveAndEvictCache() {
        Venue mockVenue = new Venue();
        ReflectionTestUtils.setField(mockVenue, "id", request.getVenueId());
        when(venueRepository.findById(request.getVenueId())).thenReturn(Optional.of(mockVenue));
        when(concertRepository.findByIdWithDetails(concertId)).thenReturn(Optional.of(mockConcert));

        ConcertResponse response = concertService.updateConcert(concertId, request, userId, false);

        assertThat(response.getId()).isEqualTo(concertId);
        verify(concertRepository).save(any(Concert.class));
        verify(concertCacheService).evict(concertId);
        verify(concertCacheService).evictList();
    }

    @Test
    void publishConcert_ShouldSaveAndEvictCacheAndEmitEvent() {
        when(concertRepository.findById(concertId)).thenReturn(Optional.of(mockConcert));

        ConcertResponse response = concertService.publishConcert(concertId, userId, false);

        assertThat(response.getStatus()).isEqualTo(ConcertStatus.PUBLISHED);
        verify(concertRepository).save(mockConcert);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(concertCacheService).evict(concertId);
        verify(concertCacheService).evictList();
    }

    @Test
    void cancelConcert_ShouldSaveAndEvictCacheAndEmitEvent() {
        mockConcert.setStatus(ConcertStatus.PUBLISHED);
        when(concertRepository.findById(concertId)).thenReturn(Optional.of(mockConcert));

        ConcertResponse response = concertService.cancelConcert(concertId, "No reason", userId, false);

        assertThat(response.getStatus()).isEqualTo(ConcertStatus.CANCELLED);
        verify(concertRepository).save(mockConcert);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(concertCacheService).evict(concertId);
        verify(concertCacheService).evictList();
    }
}
