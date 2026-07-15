package com.tickefy.csvingestion.modules.csvimport.client;

import java.util.UUID;

/** Minimal projection of inventory-service TicketTypeResponse needed by csv-ingestion. */
public record TicketTypeSummary(UUID id, String name) {}
