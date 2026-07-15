package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.order.BaseIntegrationTest;
import com.tickefy.order.TestJwtHelper;
import com.tickefy.order.modules.order.client.InventoryBusinessException;
import com.tickefy.order.modules.order.client.InventoryClient;
import com.tickefy.order.modules.order.client.InventoryUnavailableException;
import com.tickefy.order.modules.order.client.ReservationResult;
import com.tickefy.order.modules.order.dto.OrderResponse;
import com.tickefy.order.modules.order.entity.OrderEntity;
import com.tickefy.order.modules.order.repository.OrderRepository;
import com.tickefy.order.modules.order.service.OrderPersistence;
import com.tickefy.order.common.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for order-service — maps to AC1/AC2/AC3/M2/LIMIT/SALE_WINDOW/GET/RBAC ACs.
 * Uses Testcontainers Postgres (singleton via BaseIntegrationTest).
 * InventoryClient is mocked with @MockBean — no real HTTP to inventory.
 * StubPaymentClient is real (app.payment.stub=true).
 */
@AutoConfigureMockMvc
public class OrderIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderPersistence orderPersistence;

    @MockBean
    private InventoryClient inventoryClient;

    private static final UUID CONCERT_ID = UUID.randomUUID();
    private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    private String audienceToken;
    private String otherUserToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        audienceToken = TestJwtHelper.generateToken(USER_ID.toString(), List.of("AUDIENCE"));
        otherUserToken = TestJwtHelper.generateToken(OTHER_USER_ID.toString(), List.of("AUDIENCE"));
        adminToken = TestJwtHelper.generateToken(UUID.randomUUID().toString(), List.of("ADMIN"));
    }

    private Map<String, Object> buildOrderBody(String idempotencyKey) {
        return Map.of(
                "concertId", CONCERT_ID.toString(),
                "ticketTypeId", TICKET_TYPE_ID.toString(),
                "quantity", 2,
                "idempotencyKey", idempotencyKey);
    }

    private ReservationResult mockReservationResult() {
        return new ReservationResult(
                UUID.randomUUID(),
                3_000_000L,
                6_000_000L,
                Instant.now().plusSeconds(900),
                "VIP");
    }

    // ===== AC1: Create order — reserve OK → PAYMENT_PENDING =====

    @Test
    void createOrder_reserveOk_returnsPaymentPending() throws Exception {
        // GIVEN
        when(inventoryClient.reserve(any(), anyString())).thenReturn(mockReservationResult());
        String key = UUID.randomUUID().toString();

        // WHEN
        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(key))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.totalAmount").value(6_000_000))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(3_000_000))
                .andReturn();

        // AND DB check — use OrderPersistence (readOnly TX) to safely initialize LAZY items
        String orderId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("orderId").asText();
        UUID orderUuid = UUID.fromString(orderId);
        OrderEntity dbOrder = orderRepository.findById(orderUuid).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(dbOrder.getReservationId()).isNotNull();
        // Load via readOnly TX to safely touch LAZY collection
        OrderResponse dbResponse = orderPersistence.loadResponse(orderUuid);
        assertThat(dbResponse.items()).hasSize(1);
    }

    // ===== AC2: Idempotency — 2 POST same key → same orderId, reserve called once =====

    @Test
    void createOrder_sameIdempotencyKey_returnsSameOrder() throws Exception {
        // GIVEN
        when(inventoryClient.reserve(any(), anyString())).thenReturn(mockReservationResult());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = buildOrderBody(key);

        // First POST
        MvcResult first = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("orderId").asText();

        // Second POST — same key
        MvcResult second = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("data").path("orderId").asText();

        // THEN same order
        assertThat(orderId1).isEqualTo(orderId2);

        // reserve called exactly once (second hit returned as-is from PAYMENT_PENDING state)
        verify(inventoryClient, times(1)).reserve(any(), anyString());
    }

    // ===== AC3: Reserve SOLD_OUT → order CANCELLED, 409 =====

    @Test
    void createOrder_reserveSoldOut_orderCancelled_409() throws Exception {
        // GIVEN
        when(inventoryClient.reserve(any(), anyString()))
                .thenThrow(new InventoryBusinessException(
                        ErrorCode.TICKET_SOLD_OUT, "Ticket sold out", HttpStatus.CONFLICT, null));

        String key = UUID.randomUUID().toString();

        // WHEN
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(key))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TICKET_SOLD_OUT"));

        // DB: order must be CANCELLED (not CREATED/RESERVED)
        OrderEntity dbOrder = orderRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo("CANCELLED");
    }

    // ===== M2 infra: Inventory down → order stays CREATED, 503 + resume =====

    @Test
    void createOrder_inventoryDown_keepsCreated_503() throws Exception {
        // GIVEN — first call throws infra error
        when(inventoryClient.reserve(any(), anyString()))
                .thenThrow(new InventoryUnavailableException("Inventory service down"))
                .thenReturn(mockReservationResult()); // second call succeeds

        String key = UUID.randomUUID().toString();
        Map<String, Object> body = buildOrderBody(key);

        // First POST — inventory down
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

        // DB: order must remain CREATED (not CANCELLED)
        OrderEntity dbOrder = orderRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo("CREATED");

        // Resume: same key, inventory now OK
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

        // After resume order is PAYMENT_PENDING
        OrderEntity resumed = orderRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo("PAYMENT_PENDING");
    }

    // ===== Per-user limit exceeded → 422 =====

    @Test
    void createOrder_limitExceeded_422() throws Exception {
        // GIVEN
        when(inventoryClient.reserve(any(), anyString()))
                .thenThrow(new InventoryBusinessException(
                        ErrorCode.PER_USER_LIMIT_EXCEEDED,
                        "Per-user limit exceeded",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        Map.of("remaining", 0)));

        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(key))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PER_USER_LIMIT_EXCEEDED"));

        OrderEntity dbOrder = orderRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo("CANCELLED");
    }

    // ===== Sale window closed → 403 =====

    @Test
    void createOrder_saleWindowClosed_403() throws Exception {
        // GIVEN
        when(inventoryClient.reserve(any(), anyString()))
                .thenThrow(new InventoryBusinessException(
                        ErrorCode.SALE_WINDOW_CLOSED,
                        "Sale window closed",
                        HttpStatus.FORBIDDEN,
                        null));

        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(key))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SALE_WINDOW_CLOSED"));

        OrderEntity dbOrder = orderRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo("CANCELLED");
    }

    // ===== GET /orders/{id} owner → 200 =====

    @Test
    void getOrder_owner_200() throws Exception {
        when(inventoryClient.reserve(any(), anyString())).thenReturn(mockReservationResult());
        String key = UUID.randomUUID().toString();

        MvcResult created = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(key))))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("orderId").asText();

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + audienceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));
    }

    // ===== GET /orders/{id} wrong user → 403 =====

    @Test
    void getOrder_notOwner_403() throws Exception {
        when(inventoryClient.reserve(any(), anyString())).thenReturn(mockReservationResult());
        String key = UUID.randomUUID().toString();

        MvcResult created = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(key))))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("orderId").asText();

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isForbidden());
    }

    // ===== GET /orders/{id} missing → 404 =====

    @Test
    void getOrder_missing_404() throws Exception {
        mockMvc.perform(get("/orders/{orderId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + audienceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ===== GET /users/me/orders — only current user's orders =====

    @Test
    void getMyOrders_onlyCurrentUser() throws Exception {
        when(inventoryClient.reserve(any(), anyString())).thenReturn(mockReservationResult());

        // Create 2 orders for USER_ID
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        // Create 1 order for OTHER_USER_ID
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        // GET /users/me/orders for USER_ID — should see 2 orders
        mockMvc.perform(get("/users/me/orders")
                        .header("Authorization", "Bearer " + audienceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));

        // GET /users/me/orders for OTHER_USER_ID — should see 1 order
        mockMvc.perform(get("/users/me/orders")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    // ===== RBAC: no token → 401 =====

    @Test
    void noToken_createOrder_401() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(UUID.randomUUID().toString()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ===== RBAC: tampered token → 401 =====

    @Test
    void tamperedToken_401() throws Exception {
        String tampered = TestJwtHelper.tamperToken(audienceToken);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + tampered)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(UUID.randomUUID().toString()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    // ===== Admin can read any order =====

    @Test
    void getOrder_adminCanSeeAnyOrder() throws Exception {
        when(inventoryClient.reserve(any(), anyString())).thenReturn(mockReservationResult());
        String key = UUID.randomUUID().toString();

        MvcResult created = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildOrderBody(key))))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("orderId").asText();

        // Admin (different user) can access
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId));
    }
}
