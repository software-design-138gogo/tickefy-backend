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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
public class TicketServiceTest {

    @MockitoBean
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @MockitoBean
    private com.rabbitmq.client.ConnectionFactory amqpConnectionFactory;

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
        assertThat(ticketDto.qrTokenMasked()).isNotBlank();
        assertThat(ticketDto.ticketTypeName()).isEqualTo("General Admission");

        List<Ticket> tickets = ticketRepository.findAll();
        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).getOrderItemId()).isEqualTo("item-1");
        assertThat(UUID.fromString(tickets.get(0).getQrToken()).version()).isEqualTo(4);
    }

    @Test
    void issueTicket_idempotent_shouldReturnSameTicketForSameOrderItem() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        
        TicketDto ticket1 = ticketService.issueTicket(req);
        TicketDto ticket2 = ticketService.issueTicket(req);

        assertThat(ticket1.id()).isEqualTo(ticket2.id());
        assertThat(ticket1.qrTokenMasked()).isEqualTo(ticket2.qrTokenMasked());

        List<Ticket> tickets = ticketRepository.findAll();
        assertThat(tickets).hasSize(1);
    }

    @Test
    void issueTicket_concurrentSameOrderItem_shouldReturnSameTicketWithoutDuplicateRows() throws Exception {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");

        int numberOfThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch ready = new CountDownLatch(numberOfThreads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TicketDto>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            futures.add(executorService.submit((Callable<TicketDto>) () -> {
                ready.countDown();
                start.await();
                return ticketService.issueTicket(req);
            }));
        }

        ready.await();
        start.countDown();

        List<TicketDto> results = new ArrayList<>();
        for (Future<TicketDto> future : futures) {
            results.add(future.get());
        }
        executorService.shutdown();

        assertThat(results).hasSize(numberOfThreads);
        assertThat(results.stream().map(TicketDto::id).distinct()).hasSize(1);
        assertThat(results.stream().map(TicketDto::qrTokenMasked).distinct()).hasSize(1);
        assertThat(ticketRepository.findAll()).hasSize(1);
    }

    @Test
    void getByToken_shouldReturnTicket() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);
        String rawToken = ticketRepository.findById(ticketDto.id()).orElseThrow().getQrToken();

        TicketDto found = ticketService.getByToken(rawToken);
        assertThat(found.id()).isEqualTo(ticketDto.id());
        assertThat(found.qrTokenMasked()).isEqualTo(ticketDto.qrTokenMasked());
    }

    @Test
    void getByToken_shouldThrowWhenInvalid() {
        assertThatThrownBy(() -> ticketService.getByToken("invalid-token"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_QR_TOKEN);
    }

    @Test
    void getTicketById_whenOwner_shouldReturnTicket() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        TicketDto found = ticketService.getTicketById(ticketDto.id(), "user-1");

        assertThat(found.id()).isEqualTo(ticketDto.id());
        assertThat(found.userId()).isEqualTo("user-1");
    }

    @Test
    void getTicketById_whenDifferentUser_shouldThrowAccessDenied() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        assertThatThrownBy(() -> ticketService.getTicketById(ticketDto.id(), "user-2"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TICKET_ACCESS_DENIED);
    }

    @Test
    void cancelTicket_whenDifferentUserAndNotAdmin_shouldThrowAccessDenied() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        assertThatThrownBy(() -> ticketService.cancelTicket(ticketDto.id(), "user-2", false))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TICKET_ACCESS_DENIED);

        Ticket ticket = ticketRepository.findById(ticketDto.id()).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
    }

    @Test
    void cancelTicket_whenAdmin_shouldCancelTicket() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);

        ticketService.cancelTicket(ticketDto.id(), "admin-1", true);

        Ticket ticket = ticketRepository.findById(ticketDto.id()).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
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
    void checkIn_atomic_shouldThrowWhenTicketDoesNotExist() {
        UUID missingId = UUID.randomUUID();

        assertThatThrownBy(() -> ticketService.checkIn(missingId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void checkIn_atomic_shouldReturnCancelledWhenTicketCancelled() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);
        Ticket ticket = ticketRepository.findById(ticketDto.id()).orElseThrow();
        ticket.setStatus(TicketStatus.CANCELLED);
        ticketRepository.saveAndFlush(ticket);

        CheckInResult result = ticketService.checkIn(ticketDto.id());

        assertThat(result.result()).isEqualTo("CANCELLED_REJECTED");
    }

    @Test
    void checkIn_atomic_shouldReturnRefundedWhenTicketRefunded() {
        IssueRequest req = new IssueRequest("order-1", "item-1", "user-1", "event-1", "type-1", "GA", "General Admission");
        TicketDto ticketDto = ticketService.issueTicket(req);
        Ticket ticket = ticketRepository.findById(ticketDto.id()).orElseThrow();
        ticket.setStatus(TicketStatus.REFUNDED);
        ticketRepository.saveAndFlush(ticket);

        CheckInResult result = ticketService.checkIn(ticketDto.id());

        assertThat(result.result()).isEqualTo("REFUNDED_REJECTED");
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
