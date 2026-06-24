package com.tickefy.csvingestion.modules.csvimport.client;

import java.util.UUID;

/** Minimal projection of event-service ConcertResponse needed by csv-ingestion. */
public record ConcertSummary(UUID id, UUID organizerId, String status) {}
