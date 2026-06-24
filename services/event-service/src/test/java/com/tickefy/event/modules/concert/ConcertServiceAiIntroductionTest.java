package com.tickefy.event.modules.concert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.modules.artist.ArtistRepository;
import com.tickefy.event.modules.outbox.OutboxEventRepository;
import com.tickefy.event.modules.outbox.ProcessedMessageRepository;
import com.tickefy.event.modules.venue.VenueRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConcertServiceAiIntroductionTest {

    private ConcertRepository concertRepository;
    private ProcessedMessageRepository processedMessageRepository;
    private ConcertService concertService;

    @BeforeEach
    void setUp() {
        concertRepository = mock(ConcertRepository.class);
        processedMessageRepository = mock(ProcessedMessageRepository.class);
        concertService =
                new ConcertService(
                        concertRepository,
                        mock(VenueRepository.class),
                        mock(ArtistRepository.class),
                        mock(OutboxEventRepository.class),
                        processedMessageRepository,
                        new ObjectMapper(),
                        mock(ConcertCacheService.class));
    }

    @Test
    void skipsAiResultWhenManualIntroductionIsNewerThanRequest() {
        UUID concertId = UUID.randomUUID();
        Concert concert = concert(concertId);
        concert.setManualIntroductionUpdatedAt(Instant.parse("2026-06-24T02:00:00Z"));
        when(processedMessageRepository.existsById("message-1")).thenReturn(false);
        when(concertRepository.findByIdForUpdate(concertId)).thenReturn(Optional.of(concert));

        boolean updated =
                concertService.updateAiIntroduction(
                        concertId,
                        "AI result",
                        "message-1",
                        UUID.randomUUID(),
                        "en",
                        Instant.parse("2026-06-24T01:00:00Z"),
                        Instant.parse("2026-06-24T01:01:00Z"));

        assertThat(updated).isFalse();
        verify(concertRepository, never()).save(concert);
        verify(processedMessageRepository)
                .save(org.mockito.ArgumentMatchers.argThat(
                        message -> message.getMessageId().equals("message-1")));
    }

    @Test
    void storesContractMetadataWhenAiResultIsApplied() {
        UUID concertId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant generatedAt = Instant.parse("2026-06-24T01:01:00Z");
        Concert concert = concert(concertId);
        when(processedMessageRepository.existsById("message-2")).thenReturn(false);
        when(concertRepository.findByIdForUpdate(concertId)).thenReturn(Optional.of(concert));

        boolean updated =
                concertService.updateAiIntroduction(
                        concertId,
                        "AI result",
                        "message-2",
                        jobId,
                        "vi",
                        Instant.parse("2026-06-24T01:00:00Z"),
                        generatedAt);

        assertThat(updated).isTrue();
        assertThat(concert.getConcertIntroduction()).isEqualTo("AI result");
        assertThat(concert.getConcertIntroductionSourceJobId()).isEqualTo(jobId);
        assertThat(concert.getConcertIntroductionLanguage()).isEqualTo("vi");
        assertThat(concert.getConcertIntroductionUpdatedAt()).isEqualTo(generatedAt);
        verify(concertRepository).save(concert);
    }

    private Concert concert(UUID id) {
        Concert concert = new Concert();
        ReflectionTestUtils.setField(concert, "id", id);
        concert.setTitle("Concert");
        concert.setStatus(ConcertStatus.DRAFT);
        return concert;
    }
}
