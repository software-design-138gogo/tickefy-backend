package com.tickefy.inventory.modules.inventory.controller;

import com.tickefy.inventory.common.constants.HeaderConstants;
import com.tickefy.inventory.common.constants.InventoryApiPaths;
import com.tickefy.inventory.common.response.ApiResponse;
import com.tickefy.inventory.modules.inventory.dto.AvailabilityResponse;
import com.tickefy.inventory.modules.inventory.dto.CreateTicketTypeRequest;
import com.tickefy.inventory.modules.inventory.dto.TicketTypeResponse;
import com.tickefy.inventory.modules.inventory.service.TicketTypeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(InventoryApiPaths.TICKET_TYPES)
public class TicketTypeController {

    private final TicketTypeService ticketTypeService;

    public TicketTypeController(TicketTypeService ticketTypeService) {
        this.ticketTypeService = ticketTypeService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TicketTypeResponse>> create(
            @PathVariable UUID concertId,
            @Valid @RequestBody CreateTicketTypeRequest req) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        TicketTypeResponse data = ticketTypeService.create(concertId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data, requestId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TicketTypeResponse>>> list(
            @PathVariable UUID concertId) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        List<TicketTypeResponse> data = ticketTypeService.list(concertId);
        return ResponseEntity.ok(ApiResponse.success(data, requestId));
    }

    @GetMapping("/{ticketTypeId}/availability")
    public ResponseEntity<ApiResponse<AvailabilityResponse>> availability(
            @PathVariable UUID concertId,
            @PathVariable UUID ticketTypeId) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        AvailabilityResponse data = ticketTypeService.availability(ticketTypeId);
        return ResponseEntity.ok(ApiResponse.success(data, requestId));
    }
}
