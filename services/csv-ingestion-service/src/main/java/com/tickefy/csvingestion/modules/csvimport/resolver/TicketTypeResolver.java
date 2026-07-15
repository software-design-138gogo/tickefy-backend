package com.tickefy.csvingestion.modules.csvimport.resolver;

import com.tickefy.csvingestion.modules.csvimport.client.InventoryClient;
import com.tickefy.csvingestion.modules.csvimport.client.TicketTypeSummary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads a concert's ticket-type name->id map ONCE per import job (caller resolves per-row against
 * the returned {@link TicketTypeMap}). In-memory per-job, no Redis (spec §11).
 */
@Component
public class TicketTypeResolver {

    private final InventoryClient inventoryClient;

    public TicketTypeResolver(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    public TicketTypeMap loadForConcert(UUID concertId) {
        List<TicketTypeSummary> types = inventoryClient.getTicketTypes(concertId);
        Map<String, UUID> map = new HashMap<>();
        for (TicketTypeSummary t : types) {
            if (t.name() != null) {
                map.putIfAbsent(TicketTypeMap.normalize(t.name()), t.id());
            }
        }
        return new TicketTypeMap(map);
    }
}
