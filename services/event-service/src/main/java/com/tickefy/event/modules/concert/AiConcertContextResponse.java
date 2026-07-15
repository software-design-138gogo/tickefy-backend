package com.tickefy.event.modules.concert;

import java.time.Instant;
import java.util.UUID;

public record AiConcertContextResponse(
        UUID concertId,
        String concertName,
        UUID organizerId,
        ConcertStatus status,
        Instant currentIntroductionUpdatedAt,
        Instant manualIntroductionUpdatedAt) {

    public static AiConcertContextResponse from(Concert concert) {
        return new AiConcertContextResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getCreatedBy(),
                concert.getStatus(),
                concert.getConcertIntroductionUpdatedAt(),
                concert.getManualIntroductionUpdatedAt());
    }
}
