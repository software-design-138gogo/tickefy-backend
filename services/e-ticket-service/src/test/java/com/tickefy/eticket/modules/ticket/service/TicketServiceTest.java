package com.tickefy.eticket.modules.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.eticket.common.exception.ApiException;
import com.tickefy.eticket.common.exception.ErrorCode;
import com.tickefy.eticket.modules.ticket.dto.CheckInResult;
import com.tickefy.eticket.modules.ticket.dto.IssueRequest;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.entity.Ticket;
import com.tickefy.eticket.modules.ticket.entity.TicketStatus;
import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public class TicketServiceTest {

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository ticketRepository;

    @BeforeEach
    void setUp() {
        ticketRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        ticketRepository.deleteAll();
    }

    @Test
    void issueTicket_shouldCreateNewTicket() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        assertThat(ticketDto).isNotNull();
        assertThat(ticketDto.orderItemId()).isEqualTo("item-1");
        assertThat(ticketDto.status()).isEqualTo("ISSUED");
        assertThat(ticketDto.qrToken()).isNotBlank();

        List<Ticket> tickets = ticketRepository.findAll();
        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).getOrderItemId()).isEqualTo("item-1");
    }

    @Test
    void issueTicket_idempotent_shouldReturnSameTicketForSameOrderItem() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        
        TicketDto ticket1 = ticketService.issueTicket(req);
        TicketDto ticket2 = ticketService.issueTicket(req);

        assertThat(ticket1.id()).isEqualTo(ticket2.id());
        assertThat(ticket1.qrToken()).isEqualTo(ticket2.qrToken());

        List<Ticket> tickets = ticketRepository.findAll();
        assertThat(tickets).hasSize(1);
    }

    @Test
    void getByToken_shouldReturnTicket() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        TicketDto found = ticketService.getByToken(ticketDto.qrToken());
        assertThat(found.id()).isEqualTo(ticketDto.id());
    }

    @Test
    void getByToken_shouldThrowWhenInvalid() {
        assertThatThrownBy(() -> ticketService.getByToken("invalid-token"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_QR_TOKEN);
    }

    @Test
    void checkIn_atomic_shouldReturnAccepted() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        CheckInResult result = ticketService.checkIn(ticketDto.id());
        assertThat(result.result()).isEqualTo("ACCEPTED");

        Ticket ticket = ticketRepository.findById(ticketDto.id()).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CHECKED_IN);
        assertThat(ticket.getCheckedInAt()).isNotNull();
    }

    @Test
    void checkIn_atomic_shouldReturnDuplicateWhenAlreadyCheckedIn() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        ticketService.checkIn(ticketDto.id()); // First check-in
        CheckInResult result2 = ticketService.checkIn(ticketDto.id()); // Second check-in

        assertThat(result2.result()).isEqualTo("DUPLICATE_REJECTED");
    }

    @Test
    void checkIn_raceCondition_shouldExactlyOneThreadSucceed() throws InterruptedException {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        int numberOfThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        List<CheckInResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numberOfThreads; i++) {
            executorService.execute(() -> {
                try {
                    results.add(ticketService.checkIn(ticketDto.id()));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        long acceptedCount = results.stream().filter(r -> "ACCEPTED".equals(r.result())).count();
        long rejectedCount = results.stream().filter(r -> "DUPLICATE_REJECTED".equals(r.result())).count();

        assertThat(acceptedCount).isEqualTo(1);
        assertThat(rejectedCount).isEqualTo(numberOfThreads - 1);

        Ticket ticket = ticketRepository.findById(ticketDto.id()).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CHECKED_IN);
    }
}
