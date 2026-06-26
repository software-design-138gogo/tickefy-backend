package com.tickefy.event.shared.dto;

import java.time.Instant;
import java.util.UUID;

public record ConcertUpcomingPayload(
        UUID concertId,
        String concertTitle,
        Instant eventDateTime
) {}
