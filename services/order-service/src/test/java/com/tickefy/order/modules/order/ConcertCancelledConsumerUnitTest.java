package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.order.modules.order.messaging.ConcertCancelledConsumer;
import com.tickefy.order.modules.order.messaging.OrderEvents;
import com.tickefy.order.modules.order.service.OrderPersistence;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test for ConcertCancelledConsumer parse + dispatch + bad-payload handling.
 * No Spring context, no broker.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ConcertCancelledConsumerUnitTest {

    @Mock
    private OrderPersistence orderPersistence;

    private OrderEvents.ConcertCancelledEnvelope envelope(String concertId) {
        return new OrderEvents.ConcertCancelledEnvelope(
                UUID.randomUUID().toString(),
                "ConcertCancelled",
                "1.0",
                new OrderEvents.ConcertCancelledPayload(concertId, "2026-06-27T00:00:00Z", "venue-issue"));
    }

    @Test
    void validEnvelope_dispatchesConcertIdToPersistence() {
        UUID concertId = UUID.randomUUID();
        when(orderPersistence.markConcertOrdersRefundPending(concertId)).thenReturn(3);
        ConcertCancelledConsumer consumer = new ConcertCancelledConsumer(orderPersistence);

        consumer.onConcertCancelled(envelope(concertId.toString()));

        verify(orderPersistence).markConcertOrdersRefundPending(concertId);
    }

    @Test
    void nullPayload_throws_noDispatch() {
        ConcertCancelledConsumer consumer = new ConcertCancelledConsumer(orderPersistence);
        OrderEvents.ConcertCancelledEnvelope bad =
                new OrderEvents.ConcertCancelledEnvelope(UUID.randomUUID().toString(), "ConcertCancelled", "1.0", null);

        assertThatThrownBy(() -> consumer.onConcertCancelled(bad))
                .isInstanceOf(IllegalArgumentException.class);
        verify(orderPersistence, never()).markConcertOrdersRefundPending(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nullConcertId_throws_noDispatch() {
        ConcertCancelledConsumer consumer = new ConcertCancelledConsumer(orderPersistence);

        assertThatThrownBy(() -> consumer.onConcertCancelled(envelope(null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(orderPersistence, never()).markConcertOrdersRefundPending(org.mockito.ArgumentMatchers.any());
    }
}
