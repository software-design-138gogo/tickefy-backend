package com.tickefy.csvingestion.modules.csvimport.event;

/** Event type names + routing keys for CSV import events (§6.4/§6.5). */
public final class CsvEvents {

    private CsvEvents() {}

    public static final class EventType {
        public static final String VIP_GUEST_IMPORT_COMPLETED = "VipGuestImportCompleted";
        public static final String VIP_GUEST_IMPORT_FAILED = "VipGuestImportFailed";

        private EventType() {}
    }

    /** Routing keys (used by the publisher in T5b; not stored in the outbox row). */
    public static final class RoutingKey {
        public static final String COMPLETED = "vip-guest-import.completed";
        public static final String FAILED = "vip-guest-import.failed";

        private RoutingKey() {}
    }
}
