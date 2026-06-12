package com.tickefy.checkin.modules.checkin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.common.constants.HeaderConstants;
import com.tickefy.checkin.modules.checkin.dto.*;
import com.tickefy.checkin.modules.checkin.entity.CheckinEvent;
import com.tickefy.checkin.modules.checkin.entity.SyncBatch;
import com.tickefy.checkin.modules.checkin.repository.CheckinEventRepository;
import com.tickefy.checkin.modules.checkin.repository.SyncBatchRepository;
import com.tickefy.checkin.infrastructure.clients.ETicketClient;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckinService {

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);
    private static final int SNAPSHOT_TTL_HOURS = 6;

    private final ETicketClient eTicketClient;
    private final CheckinEventRepository checkinEventRepository;
    private final SyncBatchRepository syncBatchRepository;
    private final ObjectMapper objectMapper;

    public CheckinService(ETicketClient eTicketClient,
                          CheckinEventRepository checkinEventRepository,
                          SyncBatchRepository syncBatchRepository,
                          ObjectMapper objectMapper) {
        this.eTicketClient = eTicketClient;
        this.checkinEventRepository = checkinEventRepository;
        this.syncBatchRepository = syncBatchRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Online scan: lookup QR → validate → atomic check-in → audit log.
     * All rejections return 200 with data.result per spec.
     */
    @Transactional
    public ScanResponse scan(ScanRequest req, String staffId) {
        Instant now = Instant.now();
        String tokenMasked = mask(req.qrToken());

        // 1. Lookup ticket
        Map<String, Object> ticket = eTicketClient.getTicketByToken(req.qrToken());
        if (ticket == null) {
            logEvent(null, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "INVALID_QR_TOKEN", false, now, null, null);
            return new ScanResponse("INVALID_QR_TOKEN", null, req.concertId(), req.gate(), now);
        }

        String ticketId   = (String) ticket.get("id");
        String eventId    = (String) ticket.get("eventId");
        String status     = (String) ticket.get("status");

        // 2. Concert match
        if (!req.concertId().equals(eventId)) {
            logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "WRONG_EVENT", false, now, null, null);
            return new ScanResponse("WRONG_EVENT", ticketId, req.concertId(), req.gate(), now);
        }

        // 3. Status checks
        if ("CANCELLED".equals(status)) {
            logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "CANCELLED_TICKET", false, now, null, null);
            return new ScanResponse("CANCELLED_TICKET", ticketId, req.concertId(), req.gate(), now);
        }
        if ("REFUNDED".equals(status)) {
            logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "REFUNDED_TICKET", false, now, null, null);
            return new ScanResponse("REFUNDED_TICKET", ticketId, req.concertId(), req.gate(), now);
        }
        if ("CHECKED_IN".equals(status)) {
            logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "DUPLICATE_REJECTED", false, now, null, null);
            return new ScanResponse("DUPLICATE_REJECTED", ticketId, req.concertId(), req.gate(), now);
        }

        // 4. Atomic check-in
        String checkInResult = eTicketClient.checkIn(ticketId);
        String result = "ACCEPTED".equals(checkInResult) ? "ACCEPTED" : "DUPLICATE_REJECTED";

        logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                result, false, now, null, null);

        log.info("Scan concertId={} ticketId={} result={} staffId={} deviceId={} gate={} qrMasked={}",
                req.concertId(), ticketId, result, staffId, req.deviceId(), req.gate(), tokenMasked);

        return new ScanResponse(result, ticketId, req.concertId(), req.gate(), now);
    }

    /**
     * Generate snapshot for offline scan.
     * Returns list of ISSUED tokens for the concert.
     * NOTE: stub — in production would query e-ticket-service for all ISSUED tickets.
     */
    public SnapshotResponse getSnapshot(String concertId, String gate) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(SNAPSHOT_TTL_HOURS * 3600L);
        // Stub: empty token list — real impl calls e-ticket-service bulk endpoint
        return new SnapshotResponse(concertId, gate, now, expiresAt, 0, List.of());
    }

    /**
     * Sync offline batch. Idempotent on syncBatchId.
     */
    @Transactional
    public SyncResponse sync(SyncRequest req, String staffId) {
        // Idempotency check
        Optional<SyncBatch> existing = syncBatchRepository.findBySyncBatchId(req.syncBatchId());
        if (existing.isPresent() && existing.get().getResultPayload() != null) {
            try {
                String payload = existing.get().getResultPayload();
                if (payload.startsWith("\"") && payload.endsWith("\"")) {
                    payload = objectMapper.readValue(payload, String.class);
                }
                return objectMapper.readValue(payload, SyncResponse.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached sync result for batchId={}", req.syncBatchId(), e);
            }
        }

        Instant now = Instant.now();
        List<SyncResponse.SyncItemResult> accepted  = new ArrayList<>();
        List<SyncResponse.SyncItemResult> rejected  = new ArrayList<>();
        List<SyncResponse.SyncItemResult> conflicts = new ArrayList<>();

        for (SyncRequest.SyncItem item : req.items()) {
            String tokenMasked = mask(item.qrToken());
            Map<String, Object> ticket = eTicketClient.getTicketByToken(item.qrToken());

            if (ticket == null) {
                rejected.add(new SyncResponse.SyncItemResult(item.qrToken(), "INVALID_QR_TOKEN", null));
                logEvent(null, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "INVALID_QR_TOKEN", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }

            String ticketId = (String) ticket.get("id");
            String eventId  = (String) ticket.get("eventId");
            String status   = (String) ticket.get("status");

            if (!req.concertId().equals(eventId)) {
                rejected.add(new SyncResponse.SyncItemResult(item.qrToken(), "WRONG_EVENT", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "WRONG_EVENT", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }

            if ("CANCELLED".equals(status)) {
                rejected.add(new SyncResponse.SyncItemResult(item.qrToken(), "CANCELLED_TICKET", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "CANCELLED_TICKET", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }
            if ("REFUNDED".equals(status)) {
                rejected.add(new SyncResponse.SyncItemResult(item.qrToken(), "REFUNDED_TICKET", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "REFUNDED_TICKET", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }
            if ("CHECKED_IN".equals(status)) {
                conflicts.add(new SyncResponse.SyncItemResult(item.qrToken(), "DUPLICATE_REJECTED", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "DUPLICATE_REJECTED", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }

            String checkInResult = eTicketClient.checkIn(ticketId);
            String result = "ACCEPTED".equals(checkInResult) ? "ACCEPTED" : "DUPLICATE_REJECTED";

            logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    result, true, item.scannedAt() != null ? item.scannedAt() : now, now,
                    req.syncBatchId());

            if ("ACCEPTED".equals(result)) {
                accepted.add(new SyncResponse.SyncItemResult(item.qrToken(), result, ticketId));
            } else {
                conflicts.add(new SyncResponse.SyncItemResult(item.qrToken(), result, ticketId));
            }
        }

        SyncResponse response = new SyncResponse(req.syncBatchId(), accepted, rejected, conflicts);

        // Persist batch for idempotency
        SyncBatch batch = existing.orElse(new SyncBatch());
        batch.setSyncBatchId(req.syncBatchId());
        batch.setDeviceId(req.deviceId());
        batch.setConcertId(req.concertId());
        batch.setGate(req.gate());
        batch.setStaffId(staffId);
        batch.setItemCount(req.items().size());
        batch.setProcessedAt(now);
        try {
            batch.setResultPayload(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("Failed to serialize sync result for batchId={}", req.syncBatchId());
        }
        syncBatchRepository.save(batch);

        return response;
    }

    private void logEvent(String ticketId, String qrTokenMasked, String concertId,
                          String staffId, String deviceId, String gate, String result,
                          boolean offline, Instant scannedAt, Instant syncedAt, String syncBatchId) {
        CheckinEvent event = new CheckinEvent();
        event.setTicketId(ticketId);
        event.setQrTokenMasked(qrTokenMasked);
        event.setConcertId(concertId);
        event.setStaffId(staffId);
        event.setDeviceId(deviceId);
        event.setGate(gate);
        event.setResult(result);
        event.setOffline(offline);
        event.setScannedAt(scannedAt);
        event.setSyncedAt(syncedAt);
        event.setSyncBatchId(syncBatchId);
        event.setRequestId(MDC.get(HeaderConstants.REQUEST_ID));
        checkinEventRepository.save(event);
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 8) return "****";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
