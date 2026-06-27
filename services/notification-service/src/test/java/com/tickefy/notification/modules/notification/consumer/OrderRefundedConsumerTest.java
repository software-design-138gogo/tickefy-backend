package com.tickefy.notification.modules.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.notification.modules.core.repository.ProcessedMessageRepository;
import com.tickefy.notification.modules.notification.strategy.NotificationContext;
import com.tickefy.notification.modules.notification.strategy.NotificationDispatcher;
import com.tickefy.notification.shared.dto.EventEnvelope;
import com.tickefy.notification.shared.dto.OrderRefundedPayload;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link OrderRefundedConsumer}: happy path, F1 dedup, null-guard. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class OrderRefundedConsumerTest {

    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private ProcessedMessageRepository processedMessageRepository;
    @InjectMocks private OrderRefundedConsumer consumer;

    private EventEnvelope<OrderRefundedPayload> validEnvelope() {
        OrderRefundedPayload payload = new OrderRefundedPayload();
        payload.setOrderId(UUID.randomUUID());
        payload.setUserId(UUID.randomUUID());
        payload.setConcertId(UUID.randomUUID());
        payload.setRefundAmount(100000L);
        payload.setPaymentTransactionId("GWTX-1");
        EventEnvelope<OrderRefundedPayload> env = new EventEnvelope<>();
        env.setMessageId(UUID.randomUUID());
        env.setEventType("OrderRefunded");
        env.setPayload(payload);
        return env;
    }

    @Test
    void happyPath_marksProcessedAndDispatchesInApp() {
        EventEnvelope<OrderRefundedPayload> env = validEnvelope();
        when(processedMessageRepository.tryMarkProcessed(any(UUID.class), eq("OrderRefunded")))
                .thenReturn(1); // first time

        consumer.handle(env);

        verify(processedMessageRepository, times(1))
                .tryMarkProcessed(env.getMessageId(), "OrderRefunded");
        ArgumentCaptor<NotificationContext> captor = ArgumentCaptor.forClass(NotificationContext.class);
        verify(notificationDispatcher, times(1)).dispatch(captor.capture());
        NotificationContext ctx = captor.getValue();
        assertThat(ctx.getNotification().getEventType()).isEqualTo("OrderRefunded");
        assertThat(ctx.getNotification().getChannel()).isEqualTo("IN_APP");
        assertThat(ctx.getNotification().getUserId()).isEqualTo(env.getPayload().getUserId());
        assertThat(ctx.getEmailTemplateName()).isEqualTo("email/order-refunded");
    }

    @Test
    void duplicateMessage_skipsDispatch() {
        EventEnvelope<OrderRefundedPayload> env = validEnvelope();
        when(processedMessageRepository.tryMarkProcessed(any(UUID.class), eq("OrderRefunded")))
                .thenReturn(0); // already processed (ON CONFLICT -> 0 rows)

        consumer.handle(env);

        verify(processedMessageRepository, times(1)).tryMarkProcessed(any(UUID.class), anyString());
        verify(notificationDispatcher, never()).dispatch(any());
    }

    @Test
    void nullPayload_skipsEverything() {
        EventEnvelope<OrderRefundedPayload> env = new EventEnvelope<>();
        env.setMessageId(UUID.randomUUID());
        env.setPayload(null);

        consumer.handle(env);

        verify(processedMessageRepository, never()).tryMarkProcessed(any(), anyString());
        verify(notificationDispatcher, never()).dispatch(any());
    }

    @Test
    void nullUserId_skipsEverything() {
        OrderRefundedPayload payload = new OrderRefundedPayload();
        payload.setOrderId(UUID.randomUUID());
        payload.setUserId(null);
        EventEnvelope<OrderRefundedPayload> env = new EventEnvelope<>();
        env.setMessageId(UUID.randomUUID());
        env.setPayload(payload);

        consumer.handle(env);

        verify(processedMessageRepository, never()).tryMarkProcessed(any(), anyString());
        verify(notificationDispatcher, never()).dispatch(any());
    }
}
