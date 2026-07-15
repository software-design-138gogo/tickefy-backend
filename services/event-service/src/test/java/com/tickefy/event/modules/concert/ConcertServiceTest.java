package com.tickefy.event.modules.concert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.common.exception.ApiException;
import com.tickefy.event.modules.artist.Artist;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

        // lenient: the unknown-ticketTypeName test throws before save() is reached.
        lenient().when(concertRepository.save(any(Concert.class))).thenReturn(mockConcert);
    }

    @Test
    void listConcerts_ShouldUseCacheService() {
        PageRequest pageable = PageRequest.of(0, 10);
        PageImpl<ConcertResponse> cached = new PageImpl<>(List.of());
        when(concertCacheService.getConcertList(null, pageable)).thenReturn(cached);

        assertThat(concertService.listConcerts(null, pageable)).isSameAs(cached);

        verify(concertCacheService).getConcertList(null, pageable);
        org.mockito.Mockito.verifyNoInteractions(concertRepository);
    }

    @Test
    void createConcert_ShouldSaveAndEvictCache() {
        Venue mockVenue = new Venue();
        ReflectionTestUtils.setField(mockVenue, "id", request.getVenueId());
        when(venueRepository.findById(request.getVenueId())).thenReturn(Optional.of(mockVenue));

        ConcertResponse response = concertService.createConcert(request, userId);

        assertThat(response.getId()).isEqualTo(concertId);
        verify(concertRepository).save(any(Concert.class));
        verify(concertCacheService).evictAfterCommit(concertId, true);
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
        verify(concertCacheService).evictAfterCommit(concertId, true);
    }

    @Test
    void publishConcert_ShouldSaveAndEvictCacheAndEmitEvent() {
        when(concertRepository.findById(concertId)).thenReturn(Optional.of(mockConcert));

        ConcertResponse response = concertService.publishConcert(concertId, userId, false);

        assertThat(response.getStatus()).isEqualTo(ConcertStatus.PUBLISHED);
        verify(concertRepository).save(mockConcert);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(concertCacheService).evictAfterCommit(concertId, true);
    }

    @Test
    void cancelConcert_ShouldSaveAndEvictCacheAndEmitEvent() {
        mockConcert.setStatus(ConcertStatus.PUBLISHED);
        when(concertRepository.findById(concertId)).thenReturn(Optional.of(mockConcert));

        ConcertResponse response = concertService.cancelConcert(concertId, "No reason", userId, false);

        assertThat(response.getStatus()).isEqualTo(ConcertStatus.CANCELLED);
        verify(concertRepository).save(mockConcert);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(concertCacheService).evictAfterCommit(concertId, true);
    }

    // ── Zone seat-map merge (updateConcert) ──────────────────────────────────

    private ConcertZone zone(String ticketTypeName, String zoneName, String svg, String url) {
        ConcertZone z = new ConcertZone();
        z.setTicketTypeName(ticketTypeName);
        z.setZoneName(zoneName);
        z.setSvgElementId(svg);
        z.setSeatMapUrl(url);
        return z;
    }

    private ConcertRequest.ZoneRequest reqZone(String ticketTypeName, String svg, String url) {
        ConcertRequest.ZoneRequest zr = new ConcertRequest.ZoneRequest();
        zr.setTicketTypeName(ticketTypeName);
        zr.setSvgElementId(svg);
        zr.setSeatMapUrl(url);
        return zr;
    }

    private void stubUpdate() {
        Venue v = new Venue();
        ReflectionTestUtils.setField(v, "id", request.getVenueId());
        when(venueRepository.findById(request.getVenueId())).thenReturn(Optional.of(v));
        when(concertRepository.findByIdWithDetails(concertId)).thenReturn(Optional.of(mockConcert));
    }

    private ConcertZone zoneNamed(String ticketTypeName) {
        return mockConcert.getZones().stream()
                .filter(z -> z.getTicketTypeName().equals(ticketTypeName))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void updateConcert_appliesZoneSeatmapByName() {
        stubUpdate();
        mockConcert.getZones().add(zone("SVIP", "SVIP Zone", null, null));
        mockConcert.getZones().add(zone("GA", "General Admission", null, null));
        request.setZones(List.of(reqZone("SVIP", "zone-svip", "http://mnio/svip.svg")));

        concertService.updateConcert(concertId, request, userId, false);

        assertThat(zoneNamed("SVIP").getSvgElementId()).isEqualTo("zone-svip");
        assertThat(zoneNamed("SVIP").getSeatMapUrl()).isEqualTo("http://mnio/svip.svg");
        assertThat(zoneNamed("SVIP").getTicketTypeName()).isEqualTo("SVIP"); // immutable
        // Zone not in request untouched.
        assertThat(zoneNamed("GA").getSvgElementId()).isNull();
        assertThat(zoneNamed("GA").getSeatMapUrl()).isNull();
    }

    @Test
    void updateConcert_unknownTicketTypeName_throwsBadRequest() {
        stubUpdate();
        mockConcert.getZones().add(zone("SVIP", "SVIP Zone", null, null));
        request.setZones(List.of(reqZone("NOPE", "x", "y")));

        assertThatThrownBy(() -> concertService.updateConcert(concertId, request, userId, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown ticketTypeName");
        verify(concertRepository, org.mockito.Mockito.never()).save(any(Concert.class));
    }

    @Test
    void updateConcert_partialZones_keepsOthers() {
        stubUpdate();
        mockConcert.getZones().add(zone("SVIP", "SVIP Zone", null, null));
        mockConcert.getZones().add(zone("VIP", "VIP Zone", null, null));
        mockConcert.getZones().add(zone("GA", "General Admission", null, null));
        request.setZones(List.of(reqZone("SVIP", "svip", "url")));

        concertService.updateConcert(concertId, request, userId, false);

        assertThat(mockConcert.getZones()).hasSize(3); // orphanRemoval NOT triggered
        assertThat(zoneNamed("VIP").getSvgElementId()).isNull();
        assertThat(zoneNamed("GA").getSvgElementId()).isNull();
    }

    @Test
    void updateConcert_nullSeatmapFields_doesNotClobber() {
        stubUpdate();
        mockConcert.getZones().add(zone("SVIP", "SVIP Zone", "old-svg", "http://old"));
        request.setZones(List.of(reqZone("SVIP", null, null))); // null-skip

        concertService.updateConcert(concertId, request, userId, false);

        assertThat(zoneNamed("SVIP").getSvgElementId()).isEqualTo("old-svg");
        assertThat(zoneNamed("SVIP").getSeatMapUrl()).isEqualTo("http://old");
    }

    @Test
    void updateConcert_nullZones_keepsExisting() {
        stubUpdate();
        mockConcert.getZones().add(zone("SVIP", "SVIP Zone", "svg", "url"));
        request.setZones(null);

        concertService.updateConcert(concertId, request, userId, false);

        assertThat(mockConcert.getZones()).hasSize(1);
        assertThat(zoneNamed("SVIP").getSvgElementId()).isEqualTo("svg");
    }

    // ── Artist assignment (updateConcert) ────────────────────────────────────

    private Artist artist(UUID id, String name) {
        Artist a = new Artist();
        ReflectionTestUtils.setField(a, "id", id);
        a.setName(name);
        return a;
    }

    @Test
    void updateConcert_appliesArtistIds_replacesExistingNotAccumulate() {
        stubUpdate();
        UUID oldId = UUID.randomUUID();
        mockConcert.getArtists().add(artist(oldId, "Old Artist"));
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        request.setArtistIds(List.of(a1, a2));
        when(artistRepository.findAllById(any()))
                .thenReturn(List.of(artist(a1, "A1"), artist(a2, "A2")));

        concertService.updateConcert(concertId, request, userId, false);

        assertThat(mockConcert.getArtists()).extracting(Artist::getId)
                .containsExactlyInAnyOrder(a1, a2);
        // Old artist removed — replaced, not accumulated.
        assertThat(mockConcert.getArtists()).extracting(Artist::getId).doesNotContain(oldId);
        verify(concertRepository).save(any(Concert.class));
    }

    @Test
    void updateConcert_nullArtistIds_keepsExisting() {
        stubUpdate();
        UUID oldId = UUID.randomUUID();
        mockConcert.getArtists().add(artist(oldId, "Keep Me"));
        request.setArtistIds(null);

        concertService.updateConcert(concertId, request, userId, false);

        assertThat(mockConcert.getArtists()).extracting(Artist::getId).containsExactly(oldId);
        verify(artistRepository, org.mockito.Mockito.never()).findAllById(any());
    }

    @Test
    void updateConcert_emptyArtistIds_clearsAll() {
        stubUpdate();
        mockConcert.getArtists().add(artist(UUID.randomUUID(), "Bye"));
        request.setArtistIds(List.of());
        when(artistRepository.findAllById(any())).thenReturn(List.of());

        concertService.updateConcert(concertId, request, userId, false);

        assertThat(mockConcert.getArtists()).isEmpty();
    }
}
