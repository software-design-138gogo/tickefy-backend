package com.tickefy.inventory.modules.inventory.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Inbound event shapes consumed by inventory-service. ENVELOPE bodies per backend-service-workflow §10:
 * {@code {messageId, eventType, eventVersion, occurredAt, payload:{orderId, items}}}. Unknown fields
 * ignored (e.g. userId/concertId/zoneId/ticketTypeName that e-ticket needs but inventory does not).
 */
public final class InventoryEvents {

    private InventoryEvents() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderPaidMessage(
            String messageId,
            String eventType,
            String eventVersion,
            String occurredAt,
            Payload payload) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Payload(String orderId, List<Item> items) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(String ticketTypeId, int quantity) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderReleaseMessage(
            String messageId,
            String eventType,
            String eventVersion,
            String occurredAt,
            Payload payload) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Payload(String orderId, List<Item> items) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(String ticketTypeId, int quantity) {}
    }
}
