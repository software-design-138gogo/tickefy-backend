package com.tickefy.inventory.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.inventory.BaseIntegrationTest;
import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.ReserveRequest;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.messaging.ConcertCancelledConsumer;
import com.tickefy.inventory.modules.inventory.messaging.InventoryEvents;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import com.tickefy.inventory.modules.inventory.service.ReservationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Integration test (Postgres Testcontainer) for the concert.cancelled → reserve-guard flow.
 *
 * <p>The consumer is invoked DIRECTLY (no broker): inventory pom has no RabbitMQ Testcontainer, and
 * the listener is disabled in the test profile (§6.12). This exercises the real bulk UPDATE + V3
 * migration + reserve guard end-to-end against a real DB.
 */
class ConcertCancelledConsumerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConcertCancelledConsumer consumer;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private TicketTypeRepository ticketTypeRepository;

    @Autowired
    private TicketTypeInventoryRepository inventoryRepository;

    @Autowired
    private TicketReservationRepository reservationRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanDb() {
        reservationRepository.deleteAllInBatch();
        inventoryRepository.deleteAllInBatch();
        ticketTypeRepository.deleteAllInBatch();
        try {
            redisTemplate.getConnectionFactory().getConnection().flushAll();
        } catch (Exception ignored) {
            // shared container — best effort
        }
    }

    private TicketTypeEntity seedTicketType(UUID concertId, boolean cancelled) {
        TicketTypeEntity tt = TicketTypeEntity.builder()
                .concertId(concertId)
                .name("GA")
                .price(100_000)
                .perUserLimit(null)
                .saleStartAt(Instant.now().minusSeconds(3_600))
                .saleEndAt(Instant.now().plusSeconds(3_600))
                .concertCancelled(cancelled)
                .build();
        return ticketTypeRepository.saveAndFlush(tt);
    }

    private InventoryEvents.ConcertCancelledMessage cancelMessage(UUID concertId) {
        return new InventoryEvents.ConcertCancelledMessage(
                UUID.randomUUID().toString(),
                "ConcertCancelled",
                "1.0",
                new InventoryEvents.ConcertCancelledMessage.Payload(
                        concertId.toString(), "2026-06-27T00:00:00Z", "cancelled"));
    }

    @Test
    void consume_marksAllTicketTypesOfConcert_andIsIdempotent() {
        UUID concertId = UUID.randomUUID();
        UUID otherConcertId = UUID.randomUUID();
        UUID tt1 = seedTicketType(concertId, false).getId();
        UUID tt2 = seedTicketType(concertId, false).getId();
        UUID untouched = seedTicketType(otherConcertId, false).getId();

        // First delivery: both ticket types of the concert flip to cancelled
        consumer.onConcertCancelled(cancelMessage(concertId));

        assertThat(ticketTypeRepository.findById(tt1).orElseThrow().isConcertCancelled()).isTrue();
        assertThat(ticketTypeRepository.findById(tt2).orElseThrow().isConcertCancelled()).isTrue();
        // Other concert untouched (isolation)
        assertThat(ticketTypeRepository.findById(untouched).orElseThrow().isConcertCancelled()).isFalse();

        // Redelivery: idempotent — final state stays cancelled, no error (state convergence, §6.9)
        consumer.onConcertCancelled(cancelMessage(concertId));

        assertThat(ticketTypeRepository.findById(tt1).orElseThrow().isConcertCancelled()).isTrue();
        assertThat(ticketTypeRepository.findById(tt2).orElseThrow().isConcertCancelled()).isTrue();
    }

    @Test
    void reserve_afterConcertCancelled_throws409() {
        UUID concertId = UUID.randomUUID();
        UUID ticketTypeId = seedTicketType(concertId, false).getId();

        // Cancel via the consumer (real bulk UPDATE path)
        consumer.onConcertCancelled(cancelMessage(concertId));

        ReserveRequest req = new ReserveRequest(UUID.randomUUID(), ticketTypeId, UUID.randomUUID(), 1);
        assertThatThrownBy(() -> reservationService.reserve(req))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.CONCERT_CANCELLED);
                });
    }
}
