package com.tickefy.event.modules.concert;

import java.time.Instant;
import java.util.UUID;

public record InternalConcertSummaryResponse(
        UUID concertId,
        String concertName,
        UUID organizerId,
        ConcertStatus status,
        Instant startsAt,
        Instant endsAt,
        boolean isPublished,
        boolean isCancelled) {

    public static InternalConcertSummaryResponse from(Concert concert) {
        return new InternalConcertSummaryResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getCreatedBy(),
                concert.getStatus(),
                concert.getEventDate(),
                null,
                concert.getStatus() == ConcertStatus.PUBLISHED,
                concert.getStatus() == ConcertStatus.CANCELLED);
    }
}
