package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.tickefy.order.modules.order.service.OrderPersistence;
import com.tickefy.order.modules.order.service.OrderService;
import com.tickefy.order.modules.order.statemachine.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for OrderService saga logic.
 * No Spring context, no DB, no Docker.
 * Covers M2 (reserve-fail classify) and M3 (idempotency resume).
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private PaymentClient paymentClient;

    private OrderService orderService;

    // Shared fixtures
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONCERT_ID = UUID.randomUUID();
    private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
    private static final String IDEMPOTENCY_KEY = "idem-key-abc123";
    private static final String BEARER = "token-stub";

    private CreateOrderRequest req;
    private OrderEntity createdEntity;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, orderPersistence, inventoryClient, paymentClient);

        req = new CreateOrderRequest(CONCERT_ID, TICKET_TYPE_ID, 2, IDEMPOTENCY_KEY);

        createdEntity = OrderEntity.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.CREATED.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(0L)
                .build();
    }

    // ---------------------------------------------------------------------------
    // M2: reserve-fail classify — InventoryBusinessException branches
    // ---------------------------------------------------------------------------

    /**
     * AC-M2-1: InventoryClient.reserve throws InventoryBusinessException(TICKET_SOLD_OUT, 409)
     * → persistence.markCancelled(orderId) MUST be called
     * → ApiException 409 TICKET_SOLD_OUT MUST be thrown
     */
    @Test
    void reserve_inventoryBusinessException_ticketSoldOut_marksCancelledAndThrows409() {
        // Arrange: no existing order
        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(orderPersistence.insertCreated(any(), eq(USER_ID), eq(CONCERT_ID), eq(IDEMPOTENCY_KEY)))
                .thenReturn(createdEntity);

        InventoryBusinessException cause = new InventoryBusinessException(
                ErrorCode.TICKET_SOLD_OUT, "Ticket sold out", HttpStatus.CONFLICT, null);
        when(inventoryClient.reserve(any(ReserveClientRequest.class), eq(BEARER))).thenThrow(cause);

        // Act + Assert: thrown exception
        assertThatThrownBy(() -> orderService.createOrder(req, USER_ID, BEARER))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.TICKET_SOLD_OUT);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        // Assert: markCancelled was called
        verify(orderPersistence).markCancelled(createdEntity.getId());
    }

    /**
     * AC-M2-2: InventoryClient.reserve throws InventoryBusinessException(PER_USER_LIMIT_EXCEEDED, 422)
     * → persistence.markCancelled(orderId) MUST be called
     * → ApiException 422 PER_USER_LIMIT_EXCEEDED MUST be thrown
     */
    @Test
    void reserve_inventoryBusinessException_perUserLimitExceeded_marksCancelledAndThrows422() {
        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(orderPersistence.insertCreated(any(), eq(USER_ID), eq(CONCERT_ID), eq(IDEMPOTENCY_KEY)))
                .thenReturn(createdEntity);

        Object details = "max 2 per user";
        InventoryBusinessException cause = new InventoryBusinessException(
                ErrorCode.PER_USER_LIMIT_EXCEEDED, "Per-user limit exceeded", HttpStatus.UNPROCESSABLE_ENTITY, details);
        when(inventoryClient.reserve(any(ReserveClientRequest.class), eq(BEARER))).thenThrow(cause);

        assertThatThrownBy(() -> orderService.createOrder(req, USER_ID, BEARER))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.PER_USER_LIMIT_EXCEEDED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getDetails()).isEqualTo(details);
                });

        verify(orderPersistence).markCancelled(createdEntity.getId());
    }

    /**
     * AC-M2-3: InventoryClient.reserve throws InventoryBusinessException(SALE_WINDOW_CLOSED, 403)
     * → persistence.markCancelled(orderId) MUST be called
     * → ApiException 403 SALE_WINDOW_CLOSED MUST be thrown
     */
    @Test
    void reserve_inventoryBusinessException_saleWindowClosed_marksCancelledAndThrows403() {
        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(orderPersistence.insertCreated(any(), eq(USER_ID), eq(CONCERT_ID), eq(IDEMPOTENCY_KEY)))
                .thenReturn(createdEntity);

        InventoryBusinessException cause = new InventoryBusinessException(
                ErrorCode.SALE_WINDOW_CLOSED, "Sale window closed", HttpStatus.FORBIDDEN, null);
        when(inventoryClient.reserve(any(ReserveClientRequest.class), eq(BEARER))).thenThrow(cause);

        assertThatThrownBy(() -> orderService.createOrder(req, USER_ID, BEARER))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.SALE_WINDOW_CLOSED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verify(orderPersistence).markCancelled(createdEntity.getId());
    }

    /**
     * AC-M2-4: InventoryClient.reserve throws InventoryUnavailableException (503 infra fail)
     * → persistence.markCancelled MUST NOT be called (order stays CREATED)
     * → ApiException 503 SERVICE_UNAVAILABLE MUST be thrown
     */
    @Test
    void reserve_inventoryUnavailableException_doesNotCancelAndThrows503() {
        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(orderPersistence.insertCreated(any(), eq(USER_ID), eq(CONCERT_ID), eq(IDEMPOTENCY_KEY)))
                .thenReturn(createdEntity);

        when(inventoryClient.reserve(any(ReserveClientRequest.class), eq(BEARER)))
                .thenThrow(new InventoryUnavailableException("Inventory service unreachable"));

        assertThatThrownBy(() -> orderService.createOrder(req, USER_ID, BEARER))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        // CRITICAL: markCancelled MUST NOT be called — order stays CREATED for retry
        verify(orderPersistence, never()).markCancelled(any());
    }

    // ---------------------------------------------------------------------------
    // M3: idempotency resume
    // ---------------------------------------------------------------------------

    /**
     * AC-M3-1: findByIdempotencyKey returns order with status PAYMENT_PENDING
     * → return as-is via loadResponseAfterCreate; reserve NEVER called
     */
    @Test
    void idempotency_paymentPendingOrder_returnsAsIsWithoutCallingReserve() {
        OrderEntity pendingOrder = OrderEntity.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.PAYMENT_PENDING.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(50000L)
                .build();

        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(pendingOrder));

        OrderResponse expectedResponse = new OrderResponse(
                pendingOrder.getId(), OrderStatus.PAYMENT_PENDING.name(), 50000L, "https://pay.stub", null, List.of());
        when(orderPersistence.loadResponseAfterCreate(pendingOrder.getId())).thenReturn(expectedResponse);

        OrderResponse result = orderService.createOrder(req, USER_ID, BEARER);

        // inventoryClient.reserve MUST NOT be called — short-circuit
        verify(inventoryClient, never()).reserve(any(), any());
        // insertCreated MUST NOT be called — already exists
        verify(orderPersistence, never()).insertCreated(any(), any(), any(), any());
        // correct response returned
        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_PENDING.name());
        assertThat(result.orderId()).isEqualTo(pendingOrder.getId());
    }

    /**
     * AC-M3-2: findByIdempotencyKey returns terminal PAID order
     * → return as-is; reserve NEVER called
     */
    @Test
    void idempotency_paidOrder_returnsAsIsWithoutCallingReserve() {
        OrderEntity paidOrder = OrderEntity.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.PAID.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(50000L)
                .build();

        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(paidOrder));

        OrderResponse expectedResponse = new OrderResponse(
                paidOrder.getId(), OrderStatus.PAID.name(), 50000L, null, null, List.of());
        when(orderPersistence.loadResponseAfterCreate(paidOrder.getId())).thenReturn(expectedResponse);

        OrderResponse result = orderService.createOrder(req, USER_ID, BEARER);

        verify(inventoryClient, never()).reserve(any(), any());
        verify(orderPersistence, never()).insertCreated(any(), any(), any(), any());
        assertThat(result.status()).isEqualTo(OrderStatus.PAID.name());
    }

    /**
     * AC-M3-3: findByIdempotencyKey returns CREATED order → resume saga
     * reserve IS called, markReserved IS called, paymentClient IS called, markPaymentPending IS called
     */
    @Test
    void idempotency_createdOrder_resumesSagaAndCallsReserveAndPayment() {
        UUID existingOrderId = UUID.randomUUID();
        OrderEntity existingCreated = OrderEntity.builder()
                .id(existingOrderId)
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.CREATED.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(0L)
                .build();

        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existingCreated));

        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        ReservationResult reservation = new ReservationResult(reservationId, 25000L, 50000L, expiresAt, "GA");
        when(inventoryClient.reserve(any(ReserveClientRequest.class), eq(BEARER))).thenReturn(reservation);

        OrderEntity reservedEntity = OrderEntity.builder()
                .id(existingOrderId)
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.RESERVED.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(50000L)
                .reservationId(reservationId)
                .expiresAt(expiresAt)
                .build();
        when(orderPersistence.markReserved(
                        eq(existingOrderId),
                        eq(reservationId),
                        eq(50000L),
                        eq(expiresAt),
                        eq(TICKET_TYPE_ID),
                        eq(2),
                        eq(25000L),
                        eq("GA")))
                .thenReturn(reservedEntity);

        PaymentResult payment = new PaymentResult("tx-123", "https://pay.stub/tx-123", "INITIATED");
        when(paymentClient.createTransaction(any(CreatePaymentCommand.class), eq(BEARER))).thenReturn(payment);

        OrderEntity pendingEntity = OrderEntity.builder()
                .id(existingOrderId)
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.PAYMENT_PENDING.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(50000L)
                .paymentTransactionId("tx-123")
                .paymentUrl("https://pay.stub/tx-123")
                .build();
        when(orderPersistence.markPaymentPending(existingOrderId, "tx-123", "https://pay.stub/tx-123"))
                .thenReturn(pendingEntity);

        OrderResponse expectedResponse = new OrderResponse(
                existingOrderId, OrderStatus.PAYMENT_PENDING.name(), 50000L, "https://pay.stub/tx-123", null, List.of());
        when(orderPersistence.loadResponseAfterCreate(existingOrderId)).thenReturn(expectedResponse);

        OrderResponse result = orderService.createOrder(req, USER_ID, BEARER);

        // insertCreated NOT called — order already exists
        verify(orderPersistence, never()).insertCreated(any(), any(), any(), any());
        // reserve WAS called
        verify(inventoryClient).reserve(any(ReserveClientRequest.class), eq(BEARER));
        // markReserved WAS called
        verify(orderPersistence).markReserved(
                eq(existingOrderId), eq(reservationId), eq(50000L), eq(expiresAt), eq(TICKET_TYPE_ID), eq(2), eq(25000L),
                eq("GA"));
        // paymentClient WAS called with new signature
        verify(paymentClient).createTransaction(any(CreatePaymentCommand.class), eq(BEARER));
        // markPaymentPending WAS called
        verify(orderPersistence).markPaymentPending(existingOrderId, "tx-123", "https://pay.stub/tx-123");

        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_PENDING.name());
    }

    /**
     * AC-M3-4: findByIdempotencyKey returns RESERVED order → skip reserve, go directly to payment
     * reserve NOT called; paymentClient IS called; markPaymentPending IS called
     */
    @Test
    void idempotency_reservedOrder_skipsReserveAndCallsPayment() {
        UUID existingOrderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);

        OrderEntity existingReserved = OrderEntity.builder()
                .id(existingOrderId)
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.RESERVED.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(50000L)
                .reservationId(reservationId)
                .expiresAt(expiresAt)
                .build();

        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existingReserved));

        PaymentResult payment = new PaymentResult("tx-456", "https://pay.stub/tx-456", "INITIATED");
        when(paymentClient.createTransaction(any(CreatePaymentCommand.class), eq(BEARER))).thenReturn(payment);

        OrderEntity pendingEntity = OrderEntity.builder()
                .id(existingOrderId)
                .status(OrderStatus.PAYMENT_PENDING.name())
                .totalAmount(50000L)
                .paymentTransactionId("tx-456")
                .paymentUrl("https://pay.stub/tx-456")
                .build();
        when(orderPersistence.markPaymentPending(existingOrderId, "tx-456", "https://pay.stub/tx-456"))
                .thenReturn(pendingEntity);

        OrderResponse expectedResponse = new OrderResponse(
                existingOrderId, OrderStatus.PAYMENT_PENDING.name(), 50000L, "https://pay.stub/tx-456", null, List.of());
        when(orderPersistence.loadResponseAfterCreate(existingOrderId)).thenReturn(expectedResponse);

        OrderResponse result = orderService.createOrder(req, USER_ID, BEARER);

        // reserve MUST NOT be called — already RESERVED
        verify(inventoryClient, never()).reserve(any(), any());
        verify(orderPersistence, never()).insertCreated(any(), any(), any(), any());
        // payment MUST be called with new signature
        verify(paymentClient).createTransaction(any(CreatePaymentCommand.class), eq(BEARER));
        verify(orderPersistence).markPaymentPending(existingOrderId, "tx-456", "https://pay.stub/tx-456");

        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_PENDING.name());
    }
}
