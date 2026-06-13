package com.tickefy.inventory.modules.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.inventory.modules.inventory.dto.CreateTicketTypeRequest;
import com.tickefy.inventory.modules.inventory.dto.ReserveRequest;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared factory methods for building test payloads.
 */
public class InventoryTestFixture {

    public static CreateTicketTypeRequest createTicketTypeRequest(
            int totalQty, Integer perUserLimit, Instant saleStartAt, Instant saleEndAt) {
        return new CreateTicketTypeRequest(
                "TEST-TYPE",
                100000,
                totalQty,
                perUserLimit,
                saleStartAt,
                saleEndAt);
    }

    /** ON_SALE: window already open, closes far future. */
    public static CreateTicketTypeRequest onSaleRequest(int totalQty, Integer perUserLimit) {
        return createTicketTypeRequest(
                totalQty,
                perUserLimit,
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600));
    }

    /** UPCOMING: sale starts in the future. */
    public static CreateTicketTypeRequest upcomingRequest(int totalQty) {
        return createTicketTypeRequest(
                totalQty,
                null,
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200));
    }

    /** CLOSED: sale ended in the past. */
    public static CreateTicketTypeRequest closedRequest(int totalQty) {
        return createTicketTypeRequest(
                totalQty,
                null,
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600));
    }

    public static ReserveRequest reserveRequest(UUID userId, UUID ticketTypeId, UUID orderId, int qty) {
        return new ReserveRequest(userId, ticketTypeId, orderId, qty);
    }

    public static String toJson(Object obj) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
