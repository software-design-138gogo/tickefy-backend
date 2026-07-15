package com.tickefy.payment.modules.payment.dev;

import com.tickefy.payment.common.constants.HeaderConstants;
import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
import com.tickefy.payment.common.response.ApiResponse;
import com.tickefy.payment.modules.payment.dto.CallbackRequest;
import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import com.tickefy.payment.modules.payment.repository.PaymentTransactionRepository;
import com.tickefy.payment.modules.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dev/payments")
@ConditionalOnProperty(name = "app.dev.payment-sim.enabled", havingValue = "true")
public class DevPaymentController {

    private static final Logger log = LoggerFactory.getLogger(DevPaymentController.class);

    private final PaymentTransactionRepository txRepo;
    private final PaymentService paymentService;

    public DevPaymentController(
            PaymentTransactionRepository txRepo, PaymentService paymentService) {
        this.txRepo = txRepo;
        this.paymentService = paymentService;
    }

    @PostMapping("/{paymentId}/simulate-callback")
    public ApiResponse<Map<String, String>> simulateCallback(
            @PathVariable UUID paymentId,
            @RequestParam(defaultValue = "SUCCESS") String result,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);

        PaymentTransaction tx =
                txRepo.findById(paymentId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.PAYMENT_NOT_FOUND,
                                                "Payment not found: " + paymentId,
                                                HttpStatus.NOT_FOUND));

        // Build simulated callback using tx.gatewayOrderId
        CallbackRequest callbackReq =
                new CallbackRequest(
                        tx.getGatewayOrderId(),
                        "MOCK-TXN-" + paymentId.toString().replace("-", "").substring(0, 12),
                        result.toUpperCase(),
                        tx.getAmount(),
                        null // signature bypassed
                        );

        log.info(
                "DevSimulateCallback paymentId={} gatewayOrderId={} result={}",
                paymentId,
                tx.getGatewayOrderId(),
                result);

        // bypassHmac=true (dev)
        paymentService.handleCallback(callbackReq, true);
        return ApiResponse.success(Map.of("status", "simulated", "result", result.toUpperCase()), requestId);
    }
}
