package com.tickefy.order.modules.order.controller;

import com.tickefy.order.common.constants.HeaderConstants;
import com.tickefy.order.common.response.ApiResponse;
import com.tickefy.order.modules.order.dto.EnableRefundRequest;
import com.tickefy.order.modules.order.dto.RefundJobResponse;
import com.tickefy.order.modules.order.service.RefundAdminService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/admin/refund-jobs")
public class RefundAdminController {

    private final RefundAdminService refundAdminService;

    public RefundAdminController(RefundAdminService refundAdminService) {
        this.refundAdminService = refundAdminService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundJobResponse>> enableRefund(
            @Valid @RequestBody EnableRefundRequest request) {
        RefundJobResponse response = refundAdminService.enableRefund(request.concertId());
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        return ResponseEntity.ok(ApiResponse.success(response, requestId));
    }
}
