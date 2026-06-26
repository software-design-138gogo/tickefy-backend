package com.tickefy.event.modules.concert.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tickefy.event.modules.concert.Concert;
import com.tickefy.event.modules.concert.ConcertRepository;
import com.tickefy.event.modules.outbox.OutboxEvent;
import com.tickefy.event.modules.outbox.OutboxEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcertReminderJobTest {

    @Mock
    private ConcertRepository concertRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;

    private ConcertReminderJob concertReminderJob;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        concertReminderJob = new ConcertReminderJob(concertRepository, outboxEventRepository, objectMapper);
    }

    @Test
    void processUpcomingConcerts_ShouldSaveReminderAndOutboxEvent() throws Exception {
        // Arrange
        Concert concert = new Concert();
        UUID concertId = UUID.randomUUID();
        // Use reflection or just set fields if accessible. 
        // Since we don't have setId, we'll mock the Concert behavior if needed.
        // Wait, Concert doesn't have setId(). It's auto-generated.
        // But for testing we can mock the Concert object.
        Concert mockConcert = mock(Concert.class);
        when(mockConcert.getId()).thenReturn(concertId);
        when(mockConcert.getTitle()).thenReturn("Test Concert");
        when(mockConcert.getEventDate()).thenReturn(Instant.now().plus(12, ChronoUnit.HOURS));

        when(concertRepository.findUpcomingConcertsForReminder(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(mockConcert));

        // Act
        concertReminderJob.processUpcomingConcerts();

        // Assert
        verify(mockConcert).setReminderSent(true);
        verify(concertRepository).save(mockConcert);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        OutboxEvent savedEvent = outboxCaptor.getValue();
        assertEquals("ConcertUpcoming", savedEvent.getEventType());
        assertEquals(concertId.toString(), savedEvent.getAggregateId());
        assertTrue(savedEvent.getPayload().contains(concertId.toString()));
    }

    @Test
    void processUpcomingConcerts_WhenNoConcerts_ShouldDoNothing() {
        // Arrange
        when(concertRepository.findUpcomingConcertsForReminder(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        // Act
        concertReminderJob.processUpcomingConcerts();

        // Assert
        verify(outboxEventRepository, never()).save(any());
    }
}
