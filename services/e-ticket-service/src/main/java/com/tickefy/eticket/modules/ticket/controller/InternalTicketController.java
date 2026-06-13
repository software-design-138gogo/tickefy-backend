package com.tickefy.eticket.modules.ticket.controller;

import com.tickefy.eticket.common.constants.HeaderConstants;
import com.tickefy.eticket.common.response.ApiResponse;
import com.tickefy.eticket.modules.ticket.dto.CheckInResult;
import com.tickefy.eticket.modules.ticket.dto.IssueRequest;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.dto.TicketSnapshotResponse;
import com.tickefy.eticket.modules.ticket.service.TicketService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tickets")
public class InternalTicketController {

    private final TicketService ticketService;

    public InternalTicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/issue")
    public ResponseEntity<ApiResponse<TicketDto>> issue(@Valid @RequestBody IssueRequest req) {
        TicketDto dto = ticketService.issueTicket(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(dto, requestId()));
    }

    @GetMapping("/by-token/{token}")
    public ResponseEntity<ApiResponse<TicketDto>> getByToken(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getByToken(token), requestId()));
    }

    @PutMapping("/{id}/check-in")
    public ResponseEntity<ApiResponse<CheckInResult>> checkIn(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.checkIn(id), requestId()));
    }

    @GetMapping("/snapshot")
    public ResponseEntity<ApiResponse<TicketSnapshotResponse>> snapshot(@RequestParam String concertId) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getSnapshot(concertId), requestId()));
    }

    private String requestId() {
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
