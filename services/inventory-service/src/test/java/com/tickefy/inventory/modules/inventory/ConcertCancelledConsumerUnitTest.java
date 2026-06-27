package com.tickefy.inventory.modules.inventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tickefy.inventory.modules.inventory.messaging.ConcertCancelledConsumer;
import com.tickefy.inventory.modules.inventory.messaging.InventoryEvents;
import com.tickefy.inventory.modules.inventory.service.TicketTypeService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests for ConcertCancelledConsumer. No Spring context / Docker.
 * Verifies delegate-to-service on valid message and DLQ-bound throw on malformed payload.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ConcertCancelledConsumerUnitTest {

    @Mock
    private TicketTypeService ticketTypeService;

    private ConcertCancelledConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ConcertCancelledConsumer(ticketTypeService);
    }

    private InventoryEvents.ConcertCancelledMessage message(String concertId) {
        return new InventoryEvents.ConcertCancelledMessage(
                UUID.randomUUID().toString(),
                "ConcertCancelled",
                "1.0",
                new InventoryEvents.ConcertCancelledMessage.Payload(concertId, "2026-06-27T00:00:00Z", "venue closed"));
    }

    @Test
    void consume_validMessage_delegatesToService() {
        UUID concertId = UUID.randomUUID();

        consumer.onConcertCancelled(message(concertId.toString()));

        verify(ticketTypeService, times(1)).markConcertCancelled(concertId);
    }

    @Test
    void consume_redelivery_isIdempotent_delegatesEachTime() {
        // The consumer itself is stateless; idempotency lives in the bulk UPDATE (SET true again).
        // Re-delivering the same event simply re-invokes the convergent update — no error.
        UUID concertId = UUID.randomUUID();

        consumer.onConcertCancelled(message(concertId.toString()));
        consumer.onConcertCancelled(message(concertId.toString()));

        verify(ticketTypeService, times(2)).markConcertCancelled(concertId);
    }

    @Test
    void consume_nullEvent_throws_andDoesNotCallService() {
        assertThatThrownBy(() -> consumer.onConcertCancelled(null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ticketTypeService, never()).markConcertCancelled(any());
    }

    @Test
    void consume_nullPayload_throws_andDoesNotCallService() {
        InventoryEvents.ConcertCancelledMessage event =
                new InventoryEvents.ConcertCancelledMessage(UUID.randomUUID().toString(), "ConcertCancelled", "1.0", null);

        assertThatThrownBy(() -> consumer.onConcertCancelled(event))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ticketTypeService, never()).markConcertCancelled(any());
    }

    @Test
    void consume_nullConcertId_throws_andDoesNotCallService() {
        assertThatThrownBy(() -> consumer.onConcertCancelled(message(null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ticketTypeService, never()).markConcertCancelled(any());
    }
}
