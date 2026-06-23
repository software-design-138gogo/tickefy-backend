package com.tickefy.payment.modules.payment.service;

import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
import com.tickefy.payment.modules.payment.entity.PaymentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PaymentStateMachine {

    /**
     * Allowed transitions per spec §9:
     * INITIATED -> PENDING, FAILED
     * PENDING   -> SUCCESS, FAILED
     * SUCCESS   -> REFUNDED
     */
    public boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return switch (from) {
            case INITIATED -> to == PaymentStatus.PENDING || to == PaymentStatus.FAILED;
            case PENDING -> to == PaymentStatus.SUCCESS || to == PaymentStatus.FAILED;
            case SUCCESS -> to == PaymentStatus.REFUNDED;
            default -> false;
        };
    }

    public void assertTransition(PaymentStatus from, PaymentStatus to) {
        if (!canTransition(from, to)) {
            throw new ApiException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Invalid payment state transition: " + from + " -> " + to,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
