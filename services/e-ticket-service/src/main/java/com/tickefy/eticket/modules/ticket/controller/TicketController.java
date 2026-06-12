package com.tickefy.eticket.modules.ticket.controller;

import com.tickefy.eticket.common.constants.HeaderConstants;
import com.tickefy.eticket.common.response.ApiResponse;
import com.tickefy.eticket.modules.ticket.dto.CheckInResult;
import com.tickefy.eticket.modules.ticket.dto.IssueRequest;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.service.TicketService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * POST /api/tickets/issue
     * Internal endpoint — called by order-service after payment success.
     */
    @PostMapping("/issue")
    public ResponseEntity<ApiResponse<TicketDto>> issue(@Valid @RequestBody IssueRequest req) {
        TicketDto dto = ticketService.issueTicket(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(dto, requestId()));
    }

    /**
     * GET /api/tickets
     * Customer views their own tickets. userId resolved from X-User-Id header (set by gateway/JWT filter).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TicketDto>>> listByUser(
            @RequestHeader(HeaderConstants.USER_ID) String userId) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getTicketsByUser(userId), requestId()));
    }

    /**
     * GET /api/tickets/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TicketDto>> getById(
            @PathVariable UUID id,
            @RequestHeader(HeaderConstants.USER_ID) String userId) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getTicketById(id, userId), requestId()));
    }

    /**
     * GET /api/tickets/by-token/{token}
     * Internal: called by checkin-service to resolve QR token.
     */
    @GetMapping("/by-token/{token}")
    public ResponseEntity<ApiResponse<TicketDto>> getByToken(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getByToken(token), requestId()));
    }

    /**
     * PUT /api/tickets/{id}/check-in
     * Atomic mark checked-in. Called by checkin-service.
     */
    @PutMapping("/{id}/check-in")
    public ResponseEntity<ApiResponse<CheckInResult>> checkIn(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.checkIn(id), requestId()));
    }

    /**
     * PUT /api/tickets/{id}/cancel
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable UUID id) {
        ticketService.cancelTicket(id);
        return ResponseEntity.ok(ApiResponse.success(null, requestId()));
    }

    private String requestId() {
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
