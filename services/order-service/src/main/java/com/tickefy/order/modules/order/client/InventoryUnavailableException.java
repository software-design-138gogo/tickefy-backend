package com.tickefy.order.modules.order.client;

/**
 * Thrown when Inventory is unreachable (5xx / timeout / connect error).
 * OrderService KEEPS the order in CREATED state and returns 503 to caller.
 */
public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public InventoryUnavailableException(String message) {
        super(message);
    }
}
