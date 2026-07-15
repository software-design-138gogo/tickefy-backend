package com.tickefy.payment.modules.payment.repository;

import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTransaction> findByGatewayTransactionId(String gatewayTransactionId);

    Optional<PaymentTransaction> findByGatewayOrderId(String gatewayOrderId);

    /** Refund idempotency (§10.4): replay lookup by order-side refund request id. */
    Optional<PaymentTransaction> findByRefundRequestId(String refundRequestId);

    List<PaymentTransaction> findByOrderId(UUID orderId);

    List<PaymentTransaction> findByStatusAndCreatedAtBefore(String status, Instant before, Pageable pageable);
}
