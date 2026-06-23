package com.tickefy.order.modules.order.service;

import com.tickefy.order.common.exception.ApiException;
import com.tickefy.order.common.exception.ErrorCode;
import com.tickefy.order.modules.order.client.InventoryBusinessException;
import com.tickefy.order.modules.order.client.InventoryClient;
import com.tickefy.order.modules.order.client.InventoryUnavailableException;
import com.tickefy.order.modules.order.client.CreatePaymentCommand;
import com.tickefy.order.modules.order.client.PaymentClient;
import com.tickefy.order.modules.order.client.PaymentResult;
import com.tickefy.order.modules.order.client.PaymentUnavailableException;
import com.tickefy.order.modules.order.client.ReservationResult;
import com.tickefy.order.modules.order.client.ReserveClientRequest;
import com.tickefy.order.modules.order.dto.CreateOrderRequest;
import com.tickefy.order.modules.order.dto.OrderResponse;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Non-transactional saga orchestrator.
 * HTTP calls happen OUTSIDE any @Transactional boundary.
 * Short TX1/TX2/TX3 are delegated to OrderPersistence proxy bean.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final Set<OrderStatus> TERMINAL_STATUSES = EnumSet.of(
            OrderStatus.PAID,
            OrderStatus.CANCELLED,
            OrderStatus.PAYMENT_FAILED,
            OrderStatus.EXPIRED,
            OrderStatus.REFUNDED);

    private final OrderRepository orderRepository;
    private final OrderPersistence orderPersistence;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;

    public OrderService(
            OrderRepository orderRepository,
            OrderPersistence orderPersistence,
            InventoryClient inventoryClient,
            PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.orderPersistence = orderPersistence;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
    }

    /**
     * Create or resume an order saga.
     * M1: TX boundary via OrderPersistence proxy.
     * M2: business fail → CANCELLED; infra fail → CREATED + 503.
     * M3: idempotency resume based on current status.
     */
    public OrderResponse createOrder(CreateOrderRequest req, UUID userId, String bearerToken) {
        // Step 0: idempotency check
        OrderEntity order = orderRepository.findByIdempotencyKey(req.idempotencyKey()).orElse(null);

        if (order != null) {
            OrderStatus current = OrderStatus.valueOf(order.getStatus());
            if (current == OrderStatus.PAYMENT_PENDING || TERMINAL_STATUSES.contains(current)) {
                // M3: return as-is
                log.debug("Idempotency hit: orderId={} status={} — returning as-is", order.getId(), current);
                return orderPersistence.loadResponseAfterCreate(order.getId());
            }
            // CREATED or RESERVED — resume saga
            log.debug("Idempotency hit: orderId={} status={} — resuming saga", order.getId(), current);
        } else {
            // Insert new order (TX1)
            UUID orderId = UUID.randomUUID();
            try {
                order = orderPersistence.insertCreated(orderId, userId, req.concertId(), req.idempotencyKey());
                log.debug("Order created: orderId={}", orderId);
            } catch (DataIntegrityViolationException ex) {
                // Race condition — re-read and resume
                log.debug("Race on idempotency_key={}, re-reading", req.idempotencyKey());
                order = orderRepository.findByIdempotencyKey(req.idempotencyKey())
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.INTERNAL_SERVER_ERROR,
                                "Order insert race but key not found",
                                HttpStatus.INTERNAL_SERVER_ERROR));
            }
        }

        OrderStatus currentStatus = OrderStatus.valueOf(order.getStatus());

        // Step 1: reserve if still CREATED
        if (currentStatus == OrderStatus.CREATED) {
            ReserveClientRequest reserveReq = new ReserveClientRequest(
                    userId, req.ticketTypeId(), order.getId(), req.quantity());
            ReservationResult reservation;
            try {
                reservation = inventoryClient.reserve(reserveReq, bearerToken);
            } catch (InventoryBusinessException e) {
                // Business error → CANCEL order
                log.warn("Inventory business error for orderId={}: {} {}", order.getId(), e.getErrorCode(), e.getMessage());
                orderPersistence.markCancelled(order.getId());
                throw new ApiException(e.getErrorCode(), e.getMessage(), e.getHttpStatus(), e.getDetails());
            } catch (InventoryUnavailableException e) {
                // Infra error → keep CREATED, return 503
                log.warn("Inventory unavailable for orderId={}: {}", order.getId(), e.getMessage());
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Inventory service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            }

            // TX2: CREATED → RESERVED
            order = orderPersistence.markReserved(
                    order.getId(),
                    reservation.reservationId(),
                    reservation.totalAmount(),
                    reservation.expiresAt(),
                    req.ticketTypeId(),
                    req.quantity(),
                    reservation.unitPrice());
            currentStatus = OrderStatus.RESERVED;
            log.debug("Order reserved: orderId={} reservationId={}", order.getId(), reservation.reservationId());
        }

        // Step 2: create payment if still RESERVED
        if (currentStatus == OrderStatus.RESERVED) {
            CreatePaymentCommand cmd = new CreatePaymentCommand(
                    order.getId(),
                    userId,
                    order.getTotalAmount(),
                    "VND",
                    "order-" + order.getId());
            PaymentResult payment;
            try {
                payment = paymentClient.createTransaction(cmd, bearerToken);
            } catch (PaymentUnavailableException e) {
                log.warn("Payment unavailable for orderId={}: {}", order.getId(), e.getMessage());
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Payment service unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            }
            // TX3: RESERVED → PAYMENT_PENDING
            order = orderPersistence.markPaymentPending(order.getId(), payment.transactionId(), payment.paymentUrl());
            log.debug("Order payment pending: orderId={} txId={}", order.getId(), payment.transactionId());
        }

        return orderPersistence.loadResponseAfterCreate(order.getId());
    }

    public OrderResponse getOrderForOwnerOrAdmin(UUID orderId, UUID userId, boolean isAdmin) {
        return orderPersistence.loadResponseForUser(orderId, userId, isAdmin);
    }

    public Page<OrderResponse> getOrdersForUser(UUID userId, Pageable pageable) {
        return orderPersistence.loadUserOrders(userId, pageable);
    }
}
