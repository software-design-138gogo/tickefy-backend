package com.tickefy.csvingestion.modules.csvimport.controller;

import com.tickefy.csvingestion.common.constants.HeaderConstants;
import com.tickefy.csvingestion.common.response.ApiResponse;
import com.tickefy.csvingestion.modules.csvimport.dto.VipGuestResponse;
import com.tickefy.csvingestion.modules.csvimport.service.VipGuestQueryService;
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

/**
 * Internal (service-to-service) read endpoint for VIP guests of a concert.
 *
 * <p>PII (email/name) — PROTECTED (CHECKIN_STAFF/ADMIN, see SecurityConfig) and NOT gateway-exposed
 * (gateway only routes /api/**). Empty concert returns an empty page (not 404).
 */
@RestController
@RequestMapping("/internal/concerts")
public class InternalVipGuestController {

    private final VipGuestQueryService vipGuestQueryService;

    public InternalVipGuestController(VipGuestQueryService vipGuestQueryService) {
        this.vipGuestQueryService = vipGuestQueryService;
    }

    @GetMapping("/{concertId}/vip-guests")
    public ResponseEntity<ApiResponse<Page<VipGuestResponse>>> getVipGuests(
            @PathVariable UUID concertId,
            @RequestParam(required = false) String email,
            @PageableDefault(size = 100) Pageable pageable) {
        Page<VipGuestResponse> page = vipGuestQueryService.getVipGuests(concertId, email, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, MDC.get(HeaderConstants.REQUEST_ID)));
    }
}
