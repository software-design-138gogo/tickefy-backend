package com.tickefy.order.modules.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.order.common.exception.ApiException;
import com.tickefy.order.common.exception.ErrorCode;
import com.tickefy.order.modules.order.dto.OrderResponse;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.entity.OrderItemEntity;
import com.tickefy.order.modules.order.entity.OutboxEntity;
import com.tickefy.order.modules.order.mapper.OrderMapper;
import com.tickefy.order.modules.order.messaging.OrderEvents;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.repository.OutboxRepository;
import com.tickefy.order.modules.order.statemachine.OrderStateMachine;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proxy bean holding short @Transactional methods.
 * Each method is a single TX boundary — no HTTP calls inside.
 * Called from OrderService (non-transactional orchestrator) to avoid self-invocation trap.
 */
@Service
public class OrderPersistence {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistence.class);

    private final OrderRepository orderRepository;
    private final OrderStateMachine stateMachine;
    private final OrderMapper orderMapper;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderPersistence(
            OrderRepository orderRepository,
            OrderStateMachine stateMachine,
            OrderMapper orderMapper,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.stateMachine = stateMachine;
        this.orderMapper = orderMapper;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /** TX1: insert order in CREATED state. */
    @Transactional
    public OrderEntity insertCreated(UUID orderId, UUID userId, UUID concertId, String idempotencyKey) {
        OrderEntity order = OrderEntity.builder()
                .id(orderId)
                .userId(userId)
                .concertId(concertId)
                .status(OrderStatus.CREATED.name())
                .idempotencyKey(idempotencyKey)
                .totalAmount(0L)
                .build();
        return orderRepository.save(order);
    }

    /**
     * TX2: CREATED → RESERVED. Set reservation data + insert order_item.
     * Idempotent: RESERVED → RESERVED is allowed (skip re-insert item if already RESERVED).
     */
    @Transactional
    public OrderEntity markReserved(
            UUID orderId,
            UUID reservationId,
            long totalAmount,
            Instant expiresAt,
            UUID ticketTypeId,
            int quantity,
            long unitPrice) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        OrderStatus currentStatus = OrderStatus.valueOf(order.getStatus());
        if (currentStatus == OrderStatus.RESERVED) {
            // Already reserved — idempotent, update data but don't re-insert item
            order.setReservationId(reservationId);
            order.setTotalAmount(totalAmount);
            order.setExpiresAt(expiresAt);
            return orderRepository.save(order);
        }

        stateMachine.assertTransition(currentStatus, OrderStatus.RESERVED);
        order.setStatus(OrderStatus.RESERVED.name());
        order.setReservationId(reservationId);
        order.setTotalAmount(totalAmount);
        order.setExpiresAt(expiresAt);

        OrderItemEntity item = OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .order(order)
                .ticketTypeId(ticketTypeId)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .build();
        order.getItems().add(item);

        return orderRepository.save(order);
    }

    /** TX3: RESERVED → PAYMENT_PENDING. Set payment data. */
    @Transactional
    public OrderEntity markPaymentPending(UUID orderId, String transactionId, String paymentUrl) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        stateMachine.assertTransition(OrderStatus.valueOf(order.getStatus()), OrderStatus.PAYMENT_PENDING);
        order.setStatus(OrderStatus.PAYMENT_PENDING.name());
        order.setPaymentTransactionId(transactionId);
        order.setPaymentUrl(paymentUrl);

        return orderRepository.save(order);
    }

    /** TX: → CANCELLED (from CREATED or RESERVED). */
    @Transactional
    public OrderEntity markCancelled(UUID orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        stateMachine.assertTransition(OrderStatus.valueOf(order.getStatus()), OrderStatus.CANCELLED);
        order.setStatus(OrderStatus.CANCELLED.name());

        return orderRepository.save(order);
    }

    /**
     * TX (Pass 2): PAYMENT_PENDING → PAID + write OrderPaid to outbox (same TX).
     * Idempotent: already PAID (or terminal) → no-op, no duplicate outbox row.
     *
     * @return true if transitioned (outbox written), false if skipped (idempotent replay)
     */
    @Transactional
    public boolean markPaid(UUID orderId, String paymentTransactionId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        OrderStatus current = OrderStatus.valueOf(order.getStatus());
        if (current == OrderStatus.PAID) {
            log.info("markPaid skipped (idempotent) — orderId={} already PAID", orderId);
            return false;
        }
        stateMachine.assertTransition(current, OrderStatus.PAID);
        order.setStatus(OrderStatus.PAID.name());
        if (paymentTransactionId != null) {
            order.setPaymentTransactionId(paymentTransactionId);
        }
        orderRepository.save(order);

        String now = Instant.now().toString();
        List<OrderEvents.OrderPaidItem> items = order.getItems().stream()
                .map(i -> new OrderEvents.OrderPaidItem(
                        i.getId().toString(),
                        i.getTicketTypeId().toString(),
                        i.getQuantity(),
                        null, // zoneId — nguồn Event (Dương), order chưa có
                        null)) // ticketTypeName — nguồn Event, order chưa có
                .toList();
        OrderEvents.OrderPaidPayload payload = new OrderEvents.OrderPaidPayload(
                order.getId().toString(),
                order.getUserId().toString(),
                order.getConcertId().toString(),
                now,
                items);
        OrderEvents.OrderPaidMessage msg = new OrderEvents.OrderPaidMessage(
                UUID.randomUUID().toString(),
                OrderEvents.Type.ORDER_PAID,
                OrderEvents.EVENT_VERSION,
                now,
                payload);
        writeOutbox(orderId, OrderEvents.Type.ORDER_PAID, msg);
        log.info("Order PAID + OrderPaid outbox written orderId={}", orderId);
        return true;
    }

    /**
     * TX (Pass 2): PAYMENT_PENDING → PAYMENT_FAILED + write OrderPaymentFailed to outbox.
     * Idempotent: already PAYMENT_FAILED/terminal → no-op.
     */
    @Transactional
    public boolean markPaymentFailed(UUID orderId) {
        return markReleaseTerminal(orderId, OrderStatus.PAYMENT_FAILED, OrderEvents.Type.ORDER_PAYMENT_FAILED);
    }

    /**
     * TX (Pass 2): PAYMENT_PENDING/RESERVED → EXPIRED + write OrderExpired to outbox.
     * Idempotent: already EXPIRED/terminal → no-op. Used by the expire worker.
     */
    @Transactional
    public boolean markExpired(UUID orderId) {
        return markReleaseTerminal(orderId, OrderStatus.EXPIRED, OrderEvents.Type.ORDER_EXPIRED);
    }

    private boolean markReleaseTerminal(UUID orderId, OrderStatus target, String eventType) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        OrderStatus current = OrderStatus.valueOf(order.getStatus());
        if (current == target) {
            log.info("mark{} skipped (idempotent) — orderId={} already {}", target, orderId, target);
            return false;
        }
        stateMachine.assertTransition(current, target);
        order.setStatus(target.name());
        orderRepository.save(order);

        String now = Instant.now().toString();
        List<OrderEvents.OrderReleaseItem> items = order.getItems().stream()
                .map(i -> new OrderEvents.OrderReleaseItem(i.getTicketTypeId().toString(), i.getQuantity()))
                .toList();
        OrderEvents.OrderReleasePayload payload = new OrderEvents.OrderReleasePayload(
                order.getId().toString(),
                order.getUserId().toString(),
                items);
        OrderEvents.OrderReleaseMessage msg = new OrderEvents.OrderReleaseMessage(
                UUID.randomUUID().toString(),
                eventType,
                OrderEvents.EVENT_VERSION,
                now,
                payload);
        writeOutbox(orderId, eventType, msg);
        log.info("Order {} + {} outbox written orderId={}", target, eventType, orderId);
        return true;
    }

    private void writeOutbox(UUID aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ApiException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to serialize outbox payload for " + eventType,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        OutboxEntity row = OutboxEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(json)
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
        outboxRepository.save(row);
    }

    /**
     * ReadOnly TX: load order + initialize LAZY items + map to DTO.
     * No HTTP calls inside — safe readOnly TX boundary.
     */
    @Transactional(readOnly = true)
    public OrderResponse loadResponse(UUID orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Order not found", HttpStatus.NOT_FOUND));
        return orderMapper.toResponse(order);
    }

    /**
     * ReadOnly TX: load order, enforce ownership/admin, map to DTO.
     * No HTTP calls inside — safe readOnly TX boundary.
     */
    @Transactional(readOnly = true)
    public OrderResponse loadResponseForUser(UUID orderId, UUID userId, boolean isAdmin) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Order not found", HttpStatus.NOT_FOUND));
        if (!isAdmin && !order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Access denied", HttpStatus.FORBIDDEN);
        }
        return orderMapper.toResponse(order);
    }

    /**
     * ReadOnly TX: load page of orders for user, map each to DTO with items initialized.
     * Uses Page-based findByUserId — no fetch-join to avoid in-memory pagination warning.
     * N+1 on items is acceptable for current load.
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> loadUserOrders(UUID userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable)
                .map(orderMapper::toResponse);
    }

    /**
     * ReadOnly TX: load order + map, used internally after create saga completes.
     * Replaces the private loadAndMap in OrderService (which ran outside TX).
     */
    @Transactional(readOnly = true)
    public OrderResponse loadResponseAfterCreate(UUID orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "Order disappeared after creation",
                        HttpStatus.INTERNAL_SERVER_ERROR));
        return orderMapper.toResponse(order);
    }
}
