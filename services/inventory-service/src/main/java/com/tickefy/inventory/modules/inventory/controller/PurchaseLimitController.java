package com.tickefy.inventory.modules.inventory.controller;

import com.tickefy.inventory.common.constants.HeaderConstants;
import com.tickefy.inventory.common.constants.InventoryApiPaths;
import com.tickefy.inventory.common.response.ApiResponse;
import com.tickefy.inventory.modules.inventory.dto.PurchaseLimitResponse;
import com.tickefy.inventory.modules.inventory.service.PurchaseLimitService;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(InventoryApiPaths.USERS)
public class PurchaseLimitController {

    private final PurchaseLimitService purchaseLimitService;

    public PurchaseLimitController(PurchaseLimitService purchaseLimitService) {
        this.purchaseLimitService = purchaseLimitService;
    }

    @GetMapping("/{userId}/purchase-limits")
    public ResponseEntity<ApiResponse<PurchaseLimitResponse>> getPurchaseLimit(
            @PathVariable UUID userId,
            @RequestParam UUID ticketTypeId) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        PurchaseLimitResponse data = purchaseLimitService.get(userId, ticketTypeId);
        return ResponseEntity.ok(ApiResponse.success(data, requestId));
    }
}
