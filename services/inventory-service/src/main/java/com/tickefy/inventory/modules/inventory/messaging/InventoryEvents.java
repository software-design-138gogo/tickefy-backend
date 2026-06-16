package com.tickefy.inventory.modules.inventory.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Inbound event shapes consumed by inventory-service. FLAT bodies (top-level messageId/eventType)
 * matching order-service's published order.* events. Unknown fields ignored (e.g. zoneId/ticketTypeName
 * that e-ticket needs but inventory does not).
 */
public final class InventoryEvents {

    private InventoryEvents() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderPaidMessage(
            String messageId,
            String eventType,
            String orderId,
            List<Item> items) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(String ticketTypeId, int quantity) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderReleaseMessage(
            String messageId,
            String eventType,
            String orderId,
            List<Item> items) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(String ticketTypeId, int quantity) {}
    }
}
