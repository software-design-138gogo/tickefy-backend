package com.tickefy.eticket.modules.ticket.controller;

import com.tickefy.eticket.common.constants.HeaderConstants;
import com.tickefy.eticket.common.security.AuthContext;
import com.tickefy.eticket.common.response.ApiResponse;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.dto.TicketQrResponse;
import com.tickefy.eticket.modules.ticket.service.TicketService;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final AuthContext authContext;

    public TicketController(TicketService ticketService, AuthContext authContext) {
        this.ticketService = ticketService;
        this.authContext = authContext;
    }

    /**
     * GET /api/tickets
     * Customer views their own tickets. userId resolved from JWT SecurityContext.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TicketDto>>> listByUser() {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getTicketsByUser(authContext.currentUserId()), requestId()));
    }

    /**
     * GET /api/tickets/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TicketDto>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getTicketById(id, authContext.currentUserId()), requestId()));
    }

    /**
     * GET /api/tickets/{id}/qr
     * Owner/admin-only endpoint for QR rendering. Raw QR stays out of list/detail/snapshot responses.
     */
    @GetMapping("/{id}/qr")
    public ResponseEntity<ApiResponse<TicketQrResponse>> getQrToken(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                ticketService.getQrToken(id, authContext.currentUserId(), authContext.hasRole("ADMIN")),
                requestId()));
    }

    /**
     * PUT /api/tickets/{id}/cancel
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable UUID id) {
        ticketService.cancelTicket(id, authContext.currentUserId(), authContext.hasRole("ADMIN"));
        return ResponseEntity.ok(ApiResponse.success(null, requestId()));
    }

    private String requestId() {
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
