package com.tickefy.inventory.modules.inventory.controller;

import com.tickefy.inventory.common.constants.HeaderConstants;
import com.tickefy.inventory.common.constants.InventoryApiPaths;
import com.tickefy.inventory.common.response.ApiResponse;
import com.tickefy.inventory.modules.inventory.dto.ReservationResponse;
import com.tickefy.inventory.modules.inventory.dto.ReserveRequest;
import com.tickefy.inventory.modules.inventory.service.ReservationService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(InventoryApiPaths.RESERVATIONS)
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> reserve(
            @Valid @RequestBody ReserveRequest req) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        ReservationResponse data = reservationService.reserve(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data, requestId));
    }
}
