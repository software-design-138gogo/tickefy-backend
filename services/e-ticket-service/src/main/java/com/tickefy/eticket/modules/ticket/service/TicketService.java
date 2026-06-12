package com.tickefy.eticket.modules.ticket.service;

import com.tickefy.eticket.common.exception.ApiException;
import com.tickefy.eticket.common.exception.ErrorCode;
import com.tickefy.eticket.modules.ticket.dto.CheckInResult;
import com.tickefy.eticket.modules.ticket.dto.IssueRequest;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.entity.Ticket;
import com.tickefy.eticket.modules.ticket.entity.TicketStatus;
import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;

    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    /**
     * Idempotent ticket issuance. Re-issuing same orderItemId returns existing ticket.
     */
    @Transactional
    public TicketDto issueTicket(IssueRequest req) {
        return ticketRepository.findByOrderItemId(req.orderItemId())
                .map(existing -> {
                    log.info("Replay detected for orderItemId={} ticketId={}", req.orderItemId(), existing.getId());
                    return toDto(existing);
                })
                .orElseGet(() -> {
                    Ticket ticket = new Ticket();
                    ticket.setOrderId(req.orderId());
                    ticket.setOrderItemId(req.orderItemId());
                    ticket.setUserId(req.userId());
                    ticket.setEventId(req.eventId());
                    ticket.setTicketTypeId(req.ticketTypeId());
                    ticket.setZoneId(req.zoneId());
                    ticket.setTicketName(req.ticketName());
                    ticket.setStatus(TicketStatus.ISSUED);
                    ticket.setQrToken(UUID.randomUUID().toString());
                    Ticket saved = ticketRepository.save(ticket);
                    log.info("Ticket issued ticketId={} orderId={} orderItemId={} eventId={}",
                            saved.getId(), saved.getOrderId(), saved.getOrderItemId(), saved.getEventId());
                    return toDto(saved);
                });
    }

    @Transactional(readOnly = true)
    public List<TicketDto> getTicketsByUser(String userId) {
        return ticketRepository.findByUserId(userId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public TicketDto getTicketById(UUID id, String userId) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND, "Ticket not found: " + id, HttpStatus.NOT_FOUND));
        if (!ticket.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.TICKET_ACCESS_DENIED, "Access denied to ticket: " + id, HttpStatus.FORBIDDEN);
        }
        return toDto(ticket);
    }

    /**
     * Internal: used by checkin-service to look up QR token.
     */
    @Transactional(readOnly = true)
    public TicketDto getByToken(String token) {
        return ticketRepository.findByQrToken(token)
                .map(this::toDto)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_QR_TOKEN, "Invalid QR token", HttpStatus.NOT_FOUND));
    }

    /**
     * Atomic check-in: UPDATE WHERE status=ISSUED.
     * rowsAffected=1 -> ACCEPTED, 0 -> DUPLICATE_REJECTED.
     */
    @Transactional
    public CheckInResult checkIn(UUID id) {
        int rows = ticketRepository.checkIn(id, java.time.Instant.now());
        String result = rows == 1 ? "ACCEPTED" : "DUPLICATE_REJECTED";
        log.info("CheckIn ticketId={} result={}", id, result);
        return new CheckInResult(result, id);
    }

    @Transactional
    public void cancelTicket(UUID id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND, "Ticket not found: " + id, HttpStatus.NOT_FOUND));
        if (ticket.getStatus() != TicketStatus.ISSUED) {
            throw new ApiException(ErrorCode.TICKET_INVALID_STATE, "Cannot cancel ticket in state: " + ticket.getStatus(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        ticket.setStatus(TicketStatus.CANCELLED);
        ticketRepository.save(ticket);
        log.info("Ticket cancelled ticketId={}", id);
    }

    private TicketDto toDto(Ticket t) {
        return new TicketDto(
                t.getId(), t.getOrderId(), t.getOrderItemId(), t.getUserId(),
                t.getEventId(), t.getTicketTypeId(), t.getZoneId(), t.getTicketName(),
                t.getStatus().name(), t.getQrToken(), t.getCheckedInAt(), t.getCreatedAt()
        );
    }
}
