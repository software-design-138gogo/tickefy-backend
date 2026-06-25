package com.tickefy.checkin.modules.vip.controller;

import com.tickefy.checkin.common.constants.HeaderConstants;
import com.tickefy.checkin.common.response.ApiResponse;
import com.tickefy.checkin.modules.vip.dto.VipGuestProjectionResponse;
import com.tickefy.checkin.modules.vip.service.VipProjectionService;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkin/concerts")
public class VipQueryController {

    private final VipProjectionService service;

    public VipQueryController(VipProjectionService service) {
        this.service = service;
    }

    @GetMapping("/{concertId}/vip-guests")
    public ResponseEntity<ApiResponse<Page<VipGuestProjectionResponse>>> getVipGuests(
            @PathVariable UUID concertId,
            @RequestParam(required = false) String email,
            @PageableDefault(size = 100) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                service.getVipGuests(concertId, email, pageable),
                MDC.get(HeaderConstants.REQUEST_ID)));
    }
}
