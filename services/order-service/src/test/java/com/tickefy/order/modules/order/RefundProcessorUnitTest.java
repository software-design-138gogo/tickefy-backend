package com.tickefy.order.modules.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tickefy.order.modules.order.client.PaymentClient;
import com.tickefy.order.modules.order.client.PaymentRefundException;
import com.tickefy.order.modules.order.client.PaymentUnavailableException;
import com.tickefy.order.modules.order.client.RefundRequest;
import com.tickefy.order.modules.order.client.RefundResponse;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.entity.RefundJobEntity;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.repository.RefundJobRepository;
import com.tickefy.order.modules.order.service.OrderPersistence;
import com.tickefy.order.modules.order.service.RefundProcessor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RefundProcessorUnitTest {

    @Mock RefundJobRepository refundJobRepository;
    @Mock OrderRepository orderRepository;
    @Mock PaymentClient paymentClient;
    @Mock OrderPersistence orderPersistence;

    private RefundProcessor processor;
    private UUID concertId;

    @BeforeEach
    void setUp() {
        processor = new RefundProcessor(refundJobRepository, orderRepository, paymentClient, orderPersistence);
        concertId = UUID.randomUUID();
    }

    @Test
    void noEnabledConcert_skipsOrderQueryAndHttp() {
        when(refundJobRepository.findAllByStatus("ENABLED")).thenReturn(List.of());

        processor.processRefunds();

        verifyNoInteractions(orderRepository, paymentClient, orderPersistence);
    }

    @Test
    void successfulRefund_callsHttpBeforeShortPersistenceUpdate() {
        OrderEntity order = order(100_000L);
        UUID transactionId = UUID.randomUUID();
        arrangeOrders(order);
        when(paymentClient.refund(any())).thenReturn(new RefundResponse("REFUNDED", "GW-1", transactionId));

        processor.processRefunds();

        InOrder sequence = inOrder(paymentClient, orderPersistence);
        sequence.verify(paymentClient).refund(new RefundRequest(order.getId(), "refund-" + order.getId(), 100_000L));
        sequence.verify(orderPersistence).markRefunded(order.getId(), transactionId);
        verify(orderPersistence, never()).markRefundManualReview(any());
    }

    @Test
    void twoOrders_areProcessedSequentially() {
        OrderEntity first = order(10L);
        OrderEntity second = order(20L);
        UUID firstTx = UUID.randomUUID();
        UUID secondTx = UUID.randomUUID();
        arrangeOrders(first, second);
        when(paymentClient.refund(any()))
                .thenReturn(new RefundResponse("REFUNDED", "GW-1", firstTx))
                .thenReturn(new RefundResponse("REFUNDED", "GW-2", secondTx));

        processor.processRefunds();

        InOrder sequence = inOrder(paymentClient, orderPersistence);
        sequence.verify(paymentClient).refund(any());
        sequence.verify(orderPersistence).markRefunded(first.getId(), firstTx);
        sequence.verify(paymentClient).refund(any());
        sequence.verify(orderPersistence).markRefunded(second.getId(), secondTx);
    }

    @Test
    void recognized422RefundRejected_movesToManualReview() {
        assertRecognized422("REFUND_REJECTED");
    }

    @Test
    void recognized422AmountMismatch_movesToManualReview() {
        assertRecognized422("REFUND_AMOUNT_MISMATCH");
    }

    @Test
    void paymentUnavailable_keepsPendingAndContinuesNextOrder() {
        OrderEntity first = order(10L);
        OrderEntity second = order(20L);
        UUID secondTx = UUID.randomUUID();
        arrangeOrders(first, second);
        when(paymentClient.refund(any()))
                .thenThrow(new PaymentUnavailableException("503"))
                .thenReturn(new RefundResponse("REFUNDED", "GW-2", secondTx));

        processor.processRefunds();

        verify(orderPersistence, never()).markRefunded(first.getId(), secondTx);
        verify(orderPersistence).markRefunded(second.getId(), secondTx);
    }

    @Test
    void notFoundOrConflict_keepsOrdersPendingAndContinues() {
        OrderEntity first = order(10L);
        OrderEntity second = order(20L);
        arrangeOrders(first, second);
        when(paymentClient.refund(any()))
                .thenThrow(new PaymentRefundException(404, "PAYMENT_NOT_FOUND", "missing"))
                .thenThrow(new PaymentRefundException(409, "REFUND_CONFLICT", "conflict"));

        processor.processRefunds();

        verify(paymentClient, org.mockito.Mockito.times(2)).refund(any());
        verifyNoInteractions(orderPersistence);
    }

    @Test
    void openCircuitBreaker_stopsWholeBatch() {
        OrderEntity first = order(10L);
        OrderEntity second = order(20L);
        arrangeOrders(first, second);
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("paymentRefund");
        breaker.transitionToOpenState();
        when(paymentClient.refund(any())).thenThrow(CallNotPermittedException.createCallNotPermittedException(breaker));

        processor.processRefunds();

        verify(paymentClient).refund(any());
        verifyNoInteractions(orderPersistence);
    }

    private void assertRecognized422(String code) {
        OrderEntity order = order(10L);
        arrangeOrders(order);
        when(paymentClient.refund(any())).thenThrow(new PaymentRefundException(422, code, "manual"));

        processor.processRefunds();

        verify(orderPersistence).markRefundManualReview(order.getId());
        verify(orderPersistence, never()).markRefunded(any(), any());
    }

    private void arrangeOrders(OrderEntity... orders) {
        RefundJobEntity job = RefundJobEntity.builder()
                .concertId(concertId)
                .enabledAt(Instant.now())
                .status("ENABLED")
                .build();
        when(refundJobRepository.findAllByStatus("ENABLED")).thenReturn(List.of(job));
        when(orderRepository.findTop100ByStatusAndConcertIdInOrderByCreatedAtAsc("REFUND_PENDING", List.of(concertId)))
                .thenReturn(List.of(orders));
    }

    private OrderEntity order(long amount) {
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .concertId(concertId)
                .status("REFUND_PENDING")
                .idempotencyKey(UUID.randomUUID().toString())
                .totalAmount(amount)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
