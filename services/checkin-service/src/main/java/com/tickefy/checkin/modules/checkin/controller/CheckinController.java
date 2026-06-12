package com.tickefy.checkin.modules.checkin.controller;

import com.tickefy.checkin.common.constants.HeaderConstants;
import com.tickefy.checkin.common.response.ApiResponse;
import com.tickefy.checkin.modules.checkin.dto.*;
import com.tickefy.checkin.modules.checkin.service.CheckinService;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/checkin")
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    /** POST /api/checkin/scan — online scan */
    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<ScanResponse>> scan(
            @Valid @RequestBody ScanRequest req,
            @RequestHeader(HeaderConstants.USER_ID) String staffId) {
        ScanResponse result = checkinService.scan(req, staffId);
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

    /** POST /api/checkin/sync — sync offline batch */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResponse>> sync(
            @Valid @RequestBody SyncRequest req,
            @RequestHeader(HeaderConstants.USER_ID) String staffId) {
        return ResponseEntity.ok(ApiResponse.success(
                checkinService.sync(req, staffId), requestId()));
    }

    private String requestId() {
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
