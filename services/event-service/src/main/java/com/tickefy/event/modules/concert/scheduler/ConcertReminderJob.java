package com.tickefy.event.modules.concert.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.modules.concert.Concert;
import com.tickefy.event.modules.concert.ConcertRepository;
import com.tickefy.event.modules.outbox.OutboxEvent;
import com.tickefy.event.modules.outbox.OutboxEventRepository;
import com.tickefy.event.shared.dto.ConcertUpcomingPayload;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConcertReminderJob {

    private final ConcertRepository concertRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    // @Scheduled(cron = "0 0 * * * *") // Run every hour
    @Scheduled(cron = "*/30 * * * * *")
    @Transactional
    public void processUpcomingConcerts() {
        Instant now = Instant.now();
        Instant timeLimit = now.plus(24, ChronoUnit.HOURS);

        List<Concert> upcomingConcerts = concertRepository.findUpcomingConcertsForReminder(now, timeLimit);

        if (upcomingConcerts.isEmpty()) {
            return;
        }

        log.info("Found {} upcoming concerts to send reminder", upcomingConcerts.size());

        for (Concert concert : upcomingConcerts) {
            try {
                // 1. Mark as sent
                concert.setReminderSent(true);
                concertRepository.save(concert);

                // 2. Create Payload
                ConcertUpcomingPayload payload = new ConcertUpcomingPayload(
                        concert.getId(), concert.getTitle(), concert.getEventDate());

                // 3. Save to Outbox
                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateId(concert.getId().toString())
                        .eventType("ConcertUpcoming")
                        .payload(objectMapper.writeValueAsString(payload))
                        .status("PENDING")
                        .createdAt(Instant.now())
                        .build();

                outboxEventRepository.save(outboxEvent);

                log.info("Scheduled reminder for concertId: {}", concert.getId());
            } catch (Exception e) {
                log.error("Failed to process reminder for concertId: {}", concert.getId(), e);
            }
        }
    }
}
