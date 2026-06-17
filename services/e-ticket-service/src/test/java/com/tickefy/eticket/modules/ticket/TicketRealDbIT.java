package com.tickefy.eticket.modules.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rabbitmq.client.ConnectionFactory;
import com.tickefy.eticket.modules.ticket.dto.CheckInByTokenResult;
import com.tickefy.eticket.modules.ticket.dto.CheckInResult;
import com.tickefy.eticket.modules.ticket.dto.IssueRequest;
import com.tickefy.eticket.modules.ticket.dto.TicketDto;
import com.tickefy.eticket.modules.ticket.repository.TicketRepository;
import com.tickefy.eticket.modules.ticket.service.TicketService;
import com.tickefy.eticket.support.PostgresContainerITBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class TicketRealDbIT extends PostgresContainerITBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory amqpConnectionFactory;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        ticketRepository.deleteAll();
    }

    @Test
    void flywayMigration_shouldUseConcertIdAndNoEventIdColumn() {
        Integer concertColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'eticket_service'
                  AND table_name = 'tickets'
                  AND column_name = 'concert_id'
                """, Integer.class);
        Integer eventColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'eticket_service'
                  AND table_name = 'tickets'
                  AND column_name = 'event_id'
                """, Integer.class);

        assertThat(concertColumns).isEqualTo(1);
        assertThat(eventColumns).isZero();
    }

    @Test
    void databaseConstraints_shouldRejectDuplicateOrderItemIdAndQrToken() {
        TicketDto issued = ticketService.issueTicket(issueRequest("order-1", "item-1"));
        String rawToken = ticketRepository.findById(issued.id()).orElseThrow().getQrToken();

        assertThatThrownBy(() -> insertTicket("order-2", "item-1", "qr-token-2"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insertTicket("order-3", "item-3", rawToken))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void issueTicket_shouldGenerateUuidV4TicketIdAndQrToken() {
        TicketDto issued = ticketService.issueTicket(issueRequest("order-uuid", "item-uuid"));
        String rawToken = ticketRepository.findById(issued.id()).orElseThrow().getQrToken();

        assertThat(issued.id().version()).isEqualTo(4);
        assertThat(UUID.fromString(rawToken).version()).isEqualTo(4);
    }

    @Test
    void issueTicket_whenTenConcurrentRequestsForSameOrderItem_createsOneRow() throws Exception {
        IssueRequest request = issueRequest("order-concurrent", "item-concurrent");
        var executor = Executors.newFixedThreadPool(10);
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);
        List<Callable<TicketDto>> tasks = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return ticketService.issueTicket(request);
            });
        }

        var futures = tasks.stream().map(executor::submit).toList();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<TicketDto> results = new ArrayList<>();
        for (var future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        assertThat(results.stream().map(TicketDto::id).distinct()).hasSize(1);
        assertThat(ticketRepository.findAll()).hasSize(1);
    }

    @Test
    void checkIn_whenTenConcurrentRequestsForSameTicket_acceptsExactlyOne() throws Exception {
        TicketDto issued = ticketService.issueTicket(issueRequest("order-scan", "item-scan"));
        var executor = Executors.newFixedThreadPool(10);
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);
        List<CheckInResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    results.add(ticketService.checkIn(issued.id()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(results).hasSize(10);
        assertThat(results.stream().filter(r -> "ACCEPTED".equals(r.result()))).hasSize(1);
        assertThat(results.stream().filter(r -> "DUPLICATE_REJECTED".equals(r.result()))).hasSize(9);
    }

    @Test
    void checkInByToken_whenTenConcurrentRequestsForSameTicket_acceptsExactlyOne() throws Exception {
        TicketDto issued = ticketService.issueTicket(issueRequest("order-token-scan", "item-token-scan"));
        String rawToken = ticketRepository.findById(issued.id()).orElseThrow().getQrToken();
        var executor = Executors.newFixedThreadPool(10);
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);
        List<CheckInByTokenResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    results.add(ticketService.checkInByToken(rawToken, issued.concertId()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(results).hasSize(10);
        assertThat(results.stream().filter(r -> "ACCEPTED".equals(r.result()))).hasSize(1);
        assertThat(results.stream().filter(r -> "DUPLICATE_REJECTED".equals(r.result()))).hasSize(9);
    }

    private IssueRequest issueRequest(String orderId, String orderItemId) {
        return new IssueRequest(orderId, orderItemId, "user-1", "concert-1", "type-1", "GA", "General Admission");
    }

    private void insertTicket(String orderId, String orderItemId, String qrToken) {
        jdbcTemplate.update("""
                INSERT INTO eticket_service.tickets
                    (id, order_id, order_item_id, user_id, concert_id, ticket_type_id, zone_id, ticket_name, status, qr_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), orderId, orderItemId, "user-raw", "concert-raw",
                "type-raw", "GA", "Raw Insert", "ISSUED", qrToken);
    }
}
