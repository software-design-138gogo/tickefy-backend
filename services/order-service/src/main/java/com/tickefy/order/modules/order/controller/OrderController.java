package com.tickefy.order.modules.order.controller;

import com.tickefy.order.common.constants.HeaderConstants;
import com.tickefy.order.common.response.ApiResponse;
import com.tickefy.order.modules.order.dto.AdminOrderResponse;
import com.tickefy.order.modules.order.dto.CreateOrderRequest;
import com.tickefy.order.modules.order.dto.OrderResponse;
import com.tickefy.order.modules.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    @PreAuthorize("hasAnyRole('AUDIENCE', 'ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest req,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication auth) {

        UUID userId = UUID.fromString((String) auth.getPrincipal());
        // Extract Bearer token only (strip "Bearer ")
        String bearerToken = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;

        OrderResponse response = orderService.createOrder(req, userId, bearerToken);
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, requestId));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<AdminOrderResponse>>> getAdminOrders(
            @PageableDefault(size = 50) Pageable pageable) {
        Page<AdminOrderResponse> page = orderService.getAdminOrders(pageable);
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        return ResponseEntity.ok(ApiResponse.success(page, requestId));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable UUID orderId,
            Authentication auth) {

        UUID userId = UUID.fromString((String) auth.getPrincipal());
        boolean isAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));

        OrderResponse response = orderService.getOrderForOwnerOrAdmin(orderId, userId, isAdmin);
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        return ResponseEntity.ok(ApiResponse.success(response, requestId));
    }

    @GetMapping("/users/me/orders")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getMyOrders(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {

        UUID userId = UUID.fromString((String) auth.getPrincipal());
        Page<OrderResponse> page = orderService.getOrdersForUser(userId, pageable);
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        return ResponseEntity.ok(ApiResponse.success(page, requestId));
    }
}
