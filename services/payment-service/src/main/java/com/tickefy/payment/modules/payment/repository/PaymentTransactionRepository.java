package com.tickefy.payment.modules.payment.repository;

import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTransaction> findByGatewayTransactionId(String gatewayTransactionId);

    Optional<PaymentTransaction> findByGatewayOrderId(String gatewayOrderId);

    List<PaymentTransaction> findByOrderId(UUID orderId);
}
