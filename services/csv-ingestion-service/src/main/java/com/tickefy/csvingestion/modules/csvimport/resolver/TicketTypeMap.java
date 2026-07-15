package com.tickefy.csvingestion.modules.csvimport.resolver;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-concert ticket-type name -> id lookup, built once per import job (CLAUDE §6.10: inventory
 * owns ticketTypeId; csv resolves by name). Match is case-insensitive + trimmed (Open-Q-MATCH).
 */
public final class TicketTypeMap {

    private final Map<String, UUID> byName;

    public TicketTypeMap(Map<String, UUID> byName) {
        this.byName = byName;
    }

    /** Resolve a CSV ticket_type value to a ticketTypeId, or empty if unknown. */
    public Optional<UUID> resolve(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(normalize(rawName)));
    }

    public static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
