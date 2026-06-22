package com.tickefy.payment.modules.payment.controller;

import com.tickefy.payment.common.constants.HeaderConstants;
import com.tickefy.payment.common.response.ApiResponse;
import com.tickefy.payment.modules.payment.dto.CallbackRequest;
import com.tickefy.payment.modules.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentCallbackController {

    private final PaymentService paymentService;

    public PaymentCallbackController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/callback")
    public ApiResponse<Map<String, String>> callback(
            @RequestBody CallbackRequest req, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        paymentService.handleCallback(req, false);
        return ApiResponse.success(Map.of("status", "ok"), requestId);
    }
}
