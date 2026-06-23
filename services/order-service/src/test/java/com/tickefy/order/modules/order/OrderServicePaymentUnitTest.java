package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.order.common.exception.ApiException;
import com.tickefy.order.common.exception.ErrorCode;
import com.tickefy.order.modules.order.client.CreatePaymentCommand;
import com.tickefy.order.modules.order.client.InventoryClient;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * OrderService payment-path unit tests.
 * Tests AC-os-payment-down, AC-os-happy, AC-os-cmd-mapping.
 * No Spring context, no DB, no Docker.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class OrderServicePaymentUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private PaymentClient paymentClient;

    private OrderService orderService;

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CONCERT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID TICKET_TYPE_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final String IDEMPOTENCY_KEY = "pay-test-idem-key-001";
    private static final String BEARER = "bearer-tok-xyz";
    private static final long TOTAL_AMOUNT = 250000L;
    private static final long UNIT_PRICE = 125000L;

    private CreateOrderRequest req;
    private OrderEntity reservedEntity;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, orderPersistence, inventoryClient, paymentClient);

        req = new CreateOrderRequest(CONCERT_ID, TICKET_TYPE_ID, 2, IDEMPOTENCY_KEY);

        // Pre-wire: order already in RESERVED state (skip the reserve path)
        reservedEntity = OrderEntity.builder()
                .id(ORDER_ID)
                .userId(USER_ID)
                .concertId(CONCERT_ID)
                .status(OrderStatus.RESERVED.name())
                .idempotencyKey(IDEMPOTENCY_KEY)
                .totalAmount(TOTAL_AMOUNT)
                .reservationId(UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        when(orderRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(reservedEntity));
    }

    // -----------------------------------------------------------------------
    // AC-os-payment-down: PaymentUnavailableException → ApiException 503,
    //                      markPaymentPending NEVER called, markCancelled NEVER called
    // -----------------------------------------------------------------------

    /**
     * AC-os-payment-down: paymentClient throws PaymentUnavailableException
     * → OrderService throws ApiException SERVICE_UNAVAILABLE (503)
     * → markPaymentPending is NOT called (order NOT advanced)
     * → markCancelled is NOT called (order stays RESERVED, per M2 design for infra errors)
     */
    @Test
    void acOsPaymentDown_paymentUnavailable_throws503AndNeverCallsMarkPaymentPending() {
        when(paymentClient.createTransaction(any(CreatePaymentCommand.class), eq(BEARER)))
                .thenThrow(new PaymentUnavailableException("Payment service down"));

        assertThatThrownBy(() -> orderService.createOrder(req, USER_ID, BEARER))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(api.getStatus().value()).isEqualTo(503);
                });

        // markPaymentPending MUST NOT be called — order was not advanced
        verify(orderPersistence, never()).markPaymentPending(any(), any(), any());
        // markCancelled MUST NOT be called — infra error, order stays RESERVED
        verify(orderPersistence, never()).markCancelled(any());
    }

    // -----------------------------------------------------------------------
    // AC-os-happy: PaymentResult OK → markPaymentPending called with transactionId+paymentUrl
    // -----------------------------------------------------------------------

    /**
     * AC-os-happy: paymentClient returns a valid PaymentResult
     * → markPaymentPending is called with transactionId and paymentUrl from result
     */
    @Test
    void acOsHappy_paymentSuccess_callsMarkPaymentPendingWithCorrectArgs() {
        PaymentResult paymentResult = new PaymentResult("txn-happy-001", "https://pay.example.com/txn-happy-001", "PENDING");
        when(paymentClient.createTransaction(any(CreatePaymentCommand.class), eq(BEARER)))
                .thenReturn(paymentResult);

        OrderEntity pendingEntity = OrderEntity.builder()
                .id(ORDER_ID)
                .status(OrderStatus.PAYMENT_PENDING.name())
                .totalAmount(TOTAL_AMOUNT)
                .paymentTransactionId("txn-happy-001")
                .paymentUrl("https://pay.example.com/txn-happy-001")
                .build();
        when(orderPersistence.markPaymentPending(ORDER_ID, "txn-happy-001", "https://pay.example.com/txn-happy-001"))
                .thenReturn(pendingEntity);

        OrderResponse expectedResponse = new OrderResponse(
                ORDER_ID, OrderStatus.PAYMENT_PENDING.name(), TOTAL_AMOUNT,
                "https://pay.example.com/txn-happy-001", null, List.of());
        when(orderPersistence.loadResponseAfterCreate(ORDER_ID)).thenReturn(expectedResponse);

        OrderResponse result = orderService.createOrder(req, USER_ID, BEARER);

        // markPaymentPending MUST be called with transactionId and paymentUrl from PaymentResult
        verify(orderPersistence).markPaymentPending(
                eq(ORDER_ID),
                eq("txn-happy-001"),
                eq("https://pay.example.com/txn-happy-001"));

        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_PENDING.name());
    }

    // -----------------------------------------------------------------------
    // AC-os-cmd-mapping: CreatePaymentCommand built with currency="VND",
    //                    idempotencyKey="order-"+orderId, amount=totalAmount, userId
    // -----------------------------------------------------------------------

    /**
     * AC-os-cmd-mapping: verifies the CreatePaymentCommand passed to paymentClient has:
     * - currency = "VND"
     * - idempotencyKey = "order-" + orderId
     * - amount = order.getTotalAmount()
     * - userId = the userId passed into createOrder
     * - orderId = order.getId()
     */
    @Test
    void acOsCmdMapping_createPaymentCommandBuiltWithCorrectFields() {
        PaymentResult paymentResult = new PaymentResult("txn-cmd-001", "https://pay.example.com/txn-cmd-001", "PENDING");
        when(paymentClient.createTransaction(any(CreatePaymentCommand.class), eq(BEARER)))
                .thenReturn(paymentResult);

        OrderEntity pendingEntity = OrderEntity.builder()
                .id(ORDER_ID)
                .status(OrderStatus.PAYMENT_PENDING.name())
                .totalAmount(TOTAL_AMOUNT)
                .paymentTransactionId("txn-cmd-001")
                .paymentUrl("https://pay.example.com/txn-cmd-001")
                .build();
        when(orderPersistence.markPaymentPending(any(), any(), any())).thenReturn(pendingEntity);
        when(orderPersistence.loadResponseAfterCreate(ORDER_ID)).thenReturn(
                new OrderResponse(ORDER_ID, OrderStatus.PAYMENT_PENDING.name(), TOTAL_AMOUNT, null, null, List.of()));

        orderService.createOrder(req, USER_ID, BEARER);

        // Capture the CreatePaymentCommand sent to paymentClient
        ArgumentCaptor<CreatePaymentCommand> cmdCaptor = forClass(CreatePaymentCommand.class);
        verify(paymentClient).createTransaction(cmdCaptor.capture(), eq(BEARER));

        CreatePaymentCommand captured = cmdCaptor.getValue();
        assertThat(captured.currency())
                .as("currency must be VND")
                .isEqualTo("VND");
        assertThat(captured.idempotencyKey())
                .as("idempotencyKey must be 'order-' + orderId")
                .isEqualTo("order-" + ORDER_ID);
        assertThat(captured.amount())
                .as("amount must equal order.getTotalAmount()")
                .isEqualTo(TOTAL_AMOUNT);
        assertThat(captured.userId())
                .as("userId must be forwarded from createOrder caller")
                .isEqualTo(USER_ID);
        assertThat(captured.orderId())
                .as("orderId must be the order's id")
                .isEqualTo(ORDER_ID);
    }
}
