package com.tickefy.order.modules.order.service;

import com.tickefy.order.modules.order.client.PaymentClient;
import com.tickefy.order.modules.order.client.PaymentRefundException;
import com.tickefy.order.modules.order.client.PaymentUnavailableException;
import com.tickefy.order.modules.order.client.RefundRequest;
import com.tickefy.order.modules.order.client.RefundResponse;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.entity.RefundJobEntity;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.repository.RefundJobRepository;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Non-transactional refund orchestrator. Remote HTTP always precedes the short persistence TX. */
@Service
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);
    private static final Set<String> MANUAL_REVIEW_CODES =
            Set.of("REFUND_REJECTED", "REFUND_AMOUNT_MISMATCH");

    private final RefundJobRepository refundJobRepository;
    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final OrderPersistence orderPersistence;

    public RefundProcessor(
            RefundJobRepository refundJobRepository,
            OrderRepository orderRepository,
            PaymentClient paymentClient,
            OrderPersistence orderPersistence) {
        this.refundJobRepository = refundJobRepository;
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
        this.orderPersistence = orderPersistence;
    }

    public void processRefunds() {
        List<UUID> enabledConcertIds = refundJobRepository.findAllByStatus("ENABLED").stream()
                .map(RefundJobEntity::getConcertId)
                .sorted()
                .toList();
        if (enabledConcertIds.isEmpty()) {
            return;
        }

        List<OrderEntity> orders = orderRepository.findTop100ByStatusAndConcertIdInOrderByCreatedAtAsc(
                OrderStatus.REFUND_PENDING.name(), enabledConcertIds);
        for (OrderEntity order : orders) {
            RefundRequest request = new RefundRequest(
                    order.getId(), "refund-" + order.getId(), order.getTotalAmount());
            try {
                // §8: remote HTTP is outside every DB transaction.
                RefundResponse response = paymentClient.refund(request);
                orderPersistence.markRefunded(order.getId(), response.paymentTransactionId());
            } catch (CallNotPermittedException e) {
                log.warn("paymentRefund circuit breaker open; stopping current refund batch");
                return;
            } catch (PaymentUnavailableException e) {
                log.warn("Refund temporarily unavailable orderId={} — retry next sweep: {}", order.getId(), e.getMessage());
            } catch (PaymentRefundException e) {
                handleNonRetryable(order.getId(), e);
            }
        }
    }

    private void handleNonRetryable(UUID orderId, PaymentRefundException exception) {
        if (exception.getStatusCode() == 422 && MANUAL_REVIEW_CODES.contains(exception.getErrorCode())) {
            orderPersistence.markRefundManualReview(orderId);
            log.warn("Refund requires manual review orderId={} code={}", orderId, exception.getErrorCode());
            return;
        }
        log.error(
                "Refund non-retryable response left pending orderId={} status={} code={}",
                orderId,
                exception.getStatusCode(),
                exception.getErrorCode());
    }
}
