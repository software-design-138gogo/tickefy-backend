package com.tickefy.inventory.common.constants;

public final class InventoryApiPaths {

    public static final String BASE         = "/api/inventory";
    public static final String RESERVATIONS = BASE + "/reservations";
    public static final String USERS        = BASE + "/users";
    public static final String TICKET_TYPES = BASE + "/concerts/{concertId}/ticket-types";

    private InventoryApiPaths() {}
}
