package com.tickefy.inventory.modules.inventory.service;

import com.tickefy.inventory.modules.inventory.entity.TicketReservationEntity;
import com.tickefy.inventory.modules.inventory.repository.TicketReservationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety-net sweeper: releases RESERVED reservations that have exceeded their TTL by more than
 * the configured grace period, in case the event-driven release path (OrderReleaseConsumer) failed
 * or the order was never resumed.
 *
 * <p>Runs on its own bean to avoid Spring self-invocation; each release() call is a separate TX
 * via the ReservationLifecycleService proxy (§6.2 Cách B — reaper is safety-net AFTER event path).
 *
 * <p>Default ON (matchIfMissing=true); disabled in tests via app.inventory.reaper.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "app.inventory.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationReaper {

    private static final Logger log = LoggerFactory.getLogger(ReservationReaper.class);

    private final TicketReservationRepository reservationRepository;
    private final ReservationLifecycleService lifecycleService;

    @Value("${app.inventory.reaper.grace:PT5M}")
    private Duration grace;

    @Value("${app.inventory.reaper.batch-size:100}")
    private int batchSize;

    public ReservationReaper(
            TicketReservationRepository reservationRepository,
            ReservationLifecycleService lifecycleService) {
        this.reservationRepository = reservationRepository;
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${app.inventory.reaper.delay:PT30S}")
    void reap() {
        Instant cutoff = Instant.now().minus(grace);
        List<TicketReservationEntity> stale = reservationRepository.findByStatusAndExpiresAtBefore(
                "RESERVED", cutoff, PageRequest.of(0, batchSize));
        if (stale.isEmpty()) {
            return;
        }
        log.info("reaper: {} stale RESERVED (cutoff={})", stale.size(), cutoff);
        for (TicketReservationEntity r : stale) {
            try {
                // [CHỐT §F.3] log TRƯỚC release — phân biệt release-do-reaper vs release-do-event khi soi log
                log.info("reaped orphan reservationId={} orderId={} ticketTypeId={} qty={}",
                        r.getId(), r.getOrderId(), r.getTicketTypeId(), r.getQuantity());
                lifecycleService.release(r.getOrderId(), r.getTicketTypeId(), r.getQuantity());
            } catch (Exception e) {
                log.warn("reaper skip reservationId={} err={}", r.getId(), e.toString());
            }
        }
    }
}
