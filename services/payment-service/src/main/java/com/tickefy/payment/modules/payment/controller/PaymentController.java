package com.tickefy.payment.modules.payment.controller;

import com.tickefy.payment.common.constants.HeaderConstants;
import com.tickefy.payment.common.response.ApiResponse;
import com.tickefy.payment.modules.payment.dto.CreatePaymentRequest;
import com.tickefy.payment.modules.payment.dto.CreatePaymentResponse;
import com.tickefy.payment.modules.payment.dto.RefundPaymentRequest;
import com.tickefy.payment.modules.payment.dto.RefundResponse;
import com.tickefy.payment.modules.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatePaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest req, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        CreatePaymentResponse response = paymentService.createTransaction(req);
        return ApiResponse.success(response, requestId);
    }

    /** Refund a settled payment (mảnh [3]). 200 REFUNDED; non-success via shared error envelope. */
    @PostMapping("/refund")
    public ApiResponse<RefundResponse> refund(
            @Valid @RequestBody RefundPaymentRequest req, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        RefundResponse response = paymentService.refund(req);
        return ApiResponse.success(response, requestId);
    }
}
