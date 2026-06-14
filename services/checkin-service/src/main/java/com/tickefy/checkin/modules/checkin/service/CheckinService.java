package com.tickefy.checkin.modules.checkin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.common.constants.HeaderConstants;
import com.tickefy.checkin.common.exception.ApiException;
import com.tickefy.checkin.common.exception.ErrorCode;
import com.tickefy.checkin.infrastructure.clients.ETicketClient;
import com.tickefy.checkin.modules.checkin.dto.*;
import com.tickefy.checkin.modules.checkin.entity.CheckinEvent;
import com.tickefy.checkin.modules.checkin.entity.SyncBatch;
import com.tickefy.checkin.modules.checkin.repository.CheckinEventRepository;
import com.tickefy.checkin.modules.checkin.repository.SyncBatchRepository;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CheckinService {

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);
    private static final int SNAPSHOT_TTL_HOURS = 6;

    private final ETicketClient eTicketClient;
    private final CheckinEventRepository checkinEventRepository;
    private final SyncBatchRepository syncBatchRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public CheckinService(ETicketClient eTicketClient,
                          CheckinEventRepository checkinEventRepository,
                          SyncBatchRepository syncBatchRepository,
                          ObjectMapper objectMapper,
                          TransactionTemplate transactionTemplate) {
        this.eTicketClient = eTicketClient;
        this.checkinEventRepository = checkinEventRepository;
        this.syncBatchRepository = syncBatchRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Online scan: lookup QR → validate → atomic check-in → audit log.
     * HTTP calls to e-ticket are intentionally OUTSIDE @Transactional to avoid
     * holding a DB connection open across network I/O (B-TX fix).
     * All rejections return 200 with data.result per spec.
     */
    public ScanResponse scan(ScanRequest req, String staffId) {
        Instant now = Instant.now();
        String tokenMasked = mask(req.qrToken());

        // 1. Lookup ticket (HTTP — no TX)
        Optional<ETicketClient.TicketInfo> ticketMaybe = eTicketClient.getTicketByToken(req.qrToken());
        if (ticketMaybe.isEmpty()) {
            saveScanEvent(null, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "INVALID_QR_TOKEN", false, now, null, null);
            return new ScanResponse("INVALID_QR_TOKEN", null, req.concertId(), req.gate(), now);
        }

        ETicketClient.TicketInfo ticket = ticketMaybe.get();
        String ticketId = ticket.id();
        String concertId = ticket.concertId();  // B-EVENTID fix
        String status = ticket.status();

        // 2. Concert match
        if (!req.concertId().equals(concertId)) {
            saveScanEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "WRONG_EVENT", false, now, null, null);
            return new ScanResponse("WRONG_EVENT", ticketId, req.concertId(), req.gate(), now);
        }

        // 3. Status checks
        if ("CANCELLED".equals(status)) {
            saveScanEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "CANCELLED_TICKET", false, now, null, null);
            return new ScanResponse("CANCELLED_TICKET", ticketId, req.concertId(), req.gate(), now);
        }
        if ("REFUNDED".equals(status)) {
            saveScanEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "REFUNDED_TICKET", false, now, null, null);
            return new ScanResponse("REFUNDED_TICKET", ticketId, req.concertId(), req.gate(), now);
        }
        if ("CHECKED_IN".equals(status)) {
            saveScanEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    "DUPLICATE_REJECTED", false, now, null, null);
            return new ScanResponse("DUPLICATE_REJECTED", ticketId, req.concertId(), req.gate(), now);
        }

        // 4. Atomic check-in (HTTP — no TX)
        String checkInResult = eTicketClient.checkIn(ticketId);
        String result = mapCheckInResult(checkInResult);

        saveScanEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                result, false, now, null, null);

        log.info("Scan concertId={} ticketId={} result={} staffId={} deviceId={} gate={} qrMasked={}",
                req.concertId(), ticketId, result, staffId, req.deviceId(), req.gate(), tokenMasked);

        return new ScanResponse(result, ticketId, req.concertId(), req.gate(), now);
    }

    /**
     * Generate snapshot for offline scan.
     * Returns live ISSUED tickets from e-ticket-service for the concert.
     */
    public SnapshotResponse getSnapshot(String concertId, String gate) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(SNAPSHOT_TTL_HOURS * 3600L);
        List<SnapshotResponse.SnapshotTicket> tickets = eTicketClient.getSnapshot(concertId).stream()
                .map(ticket -> new SnapshotResponse.SnapshotTicket(
                        ticket.ticketId(),
                        ticket.qrToken(),
                        ticket.concertId(),  // B-EVENTID fix
                        ticket.zoneId(),
                        ticket.zoneName(),
                        ticket.holderName(),
                        ticket.status()))
                .toList();
        return new SnapshotResponse(concertId, gate, now, expiresAt, tickets.size(), tickets);
    }

    /**
     * Sync offline batch. Idempotent on syncBatchId.
     */
    public SyncResponse sync(SyncRequest req, String staffId) {
        Instant now = Instant.now();
        Optional<SyncBatch> existing = syncBatchRepository.findBySyncBatchId(req.syncBatchId());
        if (existing.isPresent() && existing.get().getResultPayload() != null) {
            return cachedSyncResponse(existing.get());
        }
        if (existing.isPresent()) {
            return waitForCachedSyncResponse(req.syncBatchId());
        }

        SyncBatch batch = new SyncBatch();
        batch.setSyncBatchId(req.syncBatchId());
        batch.setDeviceId(req.deviceId());
        batch.setConcertId(req.concertId());
        batch.setGate(req.gate());
        batch.setStaffId(staffId);
        batch.setItemCount(req.items().size());
        try {
            batch = syncBatchRepository.saveAndFlush(batch);
        } catch (DataIntegrityViolationException ex) {
            return waitForCachedSyncResponse(req.syncBatchId());
        }

        List<SyncResponse.SyncItemResult> accepted  = new ArrayList<>();
        List<SyncResponse.SyncItemResult> rejected  = new ArrayList<>();
        List<SyncResponse.SyncItemResult> conflicts = new ArrayList<>();

        for (SyncRequest.SyncItem item : req.items()) {
            String tokenMasked = mask(item.qrToken());
            Optional<ETicketClient.TicketInfo> ticketMaybe = eTicketClient.getTicketByToken(item.qrToken());

            if (ticketMaybe.isEmpty()) {
                rejected.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, "INVALID_QR_TOKEN", null));
                logEvent(null, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "INVALID_QR_TOKEN", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }

            ETicketClient.TicketInfo ticket = ticketMaybe.get();
            String ticketId = ticket.id();
            String concertId = ticket.concertId();  // B-EVENTID fix
            String status = ticket.status();

            if (!req.concertId().equals(concertId)) {
                rejected.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, "WRONG_EVENT", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "WRONG_EVENT", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }

            if ("CANCELLED".equals(status)) {
                rejected.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, "CANCELLED_TICKET", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "CANCELLED_TICKET", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }
            if ("REFUNDED".equals(status)) {
                rejected.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, "REFUNDED_TICKET", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "REFUNDED_TICKET", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }
            if ("CHECKED_IN".equals(status)) {
                conflicts.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, "DUPLICATE_REJECTED", ticketId));
                logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                        "DUPLICATE_REJECTED", true, item.scannedAt() != null ? item.scannedAt() : now, now,
                        req.syncBatchId());
                continue;
            }

            String checkInResult = eTicketClient.checkIn(ticketId);
            String result = mapCheckInResult(checkInResult);

            logEvent(ticketId, tokenMasked, req.concertId(), staffId, req.deviceId(), req.gate(),
                    result, true, item.scannedAt() != null ? item.scannedAt() : now, now,
                    req.syncBatchId());

            if ("ACCEPTED".equals(result)) {
                accepted.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, result, ticketId));
            } else if ("DUPLICATE_REJECTED".equals(result)) {
                conflicts.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, result, ticketId));
            } else {
                rejected.add(new SyncResponse.SyncItemResult(item.localId(), tokenMasked, result, ticketId));
            }
        }

        SyncResponse response = new SyncResponse(req.syncBatchId(), req.items().size(), accepted, rejected, conflicts);

        batch.setProcessedAt(now);
        try {
            batch.setResultPayload(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("Failed to serialize sync result for batchId={}", req.syncBatchId());
        }
        syncBatchRepository.saveAndFlush(batch);

        return response;
    }

    @Transactional(readOnly = true)
    public Page<CheckinEventDto> getHistory(
            String concertId, String gate, String staffId, String result, Pageable pageable) {
        return checkinEventRepository.searchHistory(concertId, blankToNull(gate), blankToNull(staffId), blankToNull(result), pageable)
                .map(CheckinEventDto::from);
    }

    /**
     * Persist a single audit log row inside a short, dedicated transaction.
     * Keeping DB writes in a separate @Transactional method ensures
     * that network calls to e-ticket-service happen OUTSIDE any open transaction.
     */
    protected void saveScanEvent(String ticketId, String qrTokenMasked, String concertId,
                          String staffId, String deviceId, String gate, String result,
                          boolean offline, Instant scannedAt, Instant syncedAt, String syncBatchId) {
        logEvent(ticketId, qrTokenMasked, concertId, staffId, deviceId, gate,
                result, offline, scannedAt, syncedAt, syncBatchId);
    }

    private void logEvent(String ticketId, String qrTokenMasked, String concertId,
                          String staffId, String deviceId, String gate, String result,
                          boolean offline, Instant scannedAt, Instant syncedAt, String syncBatchId) {
        transactionTemplate.executeWithoutResult(status -> persistScanEvent(
                ticketId, qrTokenMasked, concertId, staffId, deviceId, gate,
                result, offline, scannedAt, syncedAt, syncBatchId));
    }

    private void persistScanEvent(String ticketId, String qrTokenMasked, String concertId,
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

    private SyncResponse cachedSyncResponse(SyncBatch batch) {
        try {
            String payload = batch.getResultPayload();
            if (payload.startsWith("\"") && payload.endsWith("\"")) {
                payload = objectMapper.readValue(payload, String.class);
            }
            return objectMapper.readValue(payload, SyncResponse.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached sync result for batchId={}", batch.getSyncBatchId(), e);
            throw new ApiException(
                    ErrorCode.SYNC_SERVICE_UNAVAILABLE,
                    "Cached sync result is unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String mapCheckInResult(String checkInResult) {
        return switch (checkInResult) {
            case "ACCEPTED" -> "ACCEPTED";
            case "TICKET_CANCELLED" -> "CANCELLED_TICKET";
            case "TICKET_REFUNDED" -> "REFUNDED_TICKET";
            case "INVALID_QR_TOKEN", "TICKET_NOT_FOUND" -> "INVALID_QR_TOKEN";
            default -> "DUPLICATE_REJECTED";
        };
    }

    private SyncResponse waitForCachedSyncResponse(String syncBatchId) {
        for (int attempt = 0; attempt < 40; attempt++) {
            Optional<SyncBatch> saved = syncBatchRepository.findBySyncBatchId(syncBatchId);
            if (saved.isPresent() && saved.get().getResultPayload() != null) {
                return cachedSyncResponse(saved.get());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ApiException(
                ErrorCode.SYNC_SERVICE_UNAVAILABLE,
                "Sync batch is still processing",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
