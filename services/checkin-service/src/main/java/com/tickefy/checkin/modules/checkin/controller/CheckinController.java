package com.tickefy.checkin.modules.checkin.controller;

import com.tickefy.checkin.common.constants.HeaderConstants;
import com.tickefy.checkin.common.security.AuthContext;
import com.tickefy.checkin.common.response.ApiResponse;
import com.tickefy.checkin.modules.checkin.dto.*;
import com.tickefy.checkin.modules.checkin.service.CheckinService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/checkin")
public class CheckinController {

    private final CheckinService checkinService;
    private final AuthContext authContext;

    public CheckinController(CheckinService checkinService, AuthContext authContext) {
        this.checkinService = checkinService;
        this.authContext = authContext;
    }

    /** POST /api/checkin/scan — online scan */
    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<ScanResponse>> scan(
            @Valid @RequestBody ScanRequest req) {
        ScanResponse result = checkinService.scan(req, authContext.currentUserId());
        return ResponseEntity.ok(ApiResponse.success(result, requestId()));
    }

    /** GET /api/checkin/snapshot/{concertId} — download offline snapshot */
    @GetMapping("/snapshot/{concertId}")
    public ResponseEntity<ApiResponse<SnapshotResponse>> snapshot(
            @PathVariable String concertId,
            @RequestParam(required = false) String gate) {
        return ResponseEntity.ok(ApiResponse.success(
                checkinService.getSnapshot(concertId, gate), requestId()));
    }

    /** POST /api/checkin/offline-snapshots — canonical mobile snapshot endpoint */
    @PostMapping("/offline-snapshots")
    public ResponseEntity<ApiResponse<SnapshotResponse>> createSnapshot(
            @Valid @RequestBody CreateSnapshotRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                checkinService.getSnapshot(req.concertId(), req.gate(), req.deviceId()), requestId()));
    }

    /** POST /api/checkin/sync — legacy alias for offline batch sync */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResponse>> sync(
            @Valid @RequestBody SyncRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                checkinService.sync(req, authContext.currentUserId()), requestId()));
    }

    /** POST /api/checkin/offline-sync-batches — canonical mobile sync endpoint */
    @PostMapping("/offline-sync-batches")
    public ResponseEntity<ApiResponse<SyncResponse>> syncOfflineBatch(
            @Valid @RequestBody SyncRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                checkinService.sync(req, authContext.currentUserId()), requestId()));
    }

    /** GET /api/checkin/events/{concertId} — audit/history for a concert */
    @GetMapping("/events/{concertId}")
    public ResponseEntity<ApiResponse<Page<CheckinEventDto>>> history(
            @PathVariable String concertId,
            @RequestParam(required = false) String gate,
            @RequestParam(required = false) String staffId,
            @RequestParam(required = false) String result,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                checkinService.getHistory(concertId, gate, staffId, result, pageable), requestId()));
    }

    private String requestId() {
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
