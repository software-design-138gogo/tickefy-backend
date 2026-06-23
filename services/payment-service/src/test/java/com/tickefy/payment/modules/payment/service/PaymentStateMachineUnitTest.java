package com.tickefy.payment.modules.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tickefy.payment.common.exception.ApiException;
import com.tickefy.payment.common.exception.ErrorCode;
import com.tickefy.payment.modules.payment.entity.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * AC-state: PaymentStateMachine transition table, assertTransition error shape.
 *
 * Spec §9 allowed transitions:
 *   INITIATED -> PENDING, FAILED
 *   PENDING   -> SUCCESS, FAILED
 *   SUCCESS   -> REFUNDED
 *   Everything else: INVALID
 */
@Tag("unit")
class PaymentStateMachineUnitTest {

    private PaymentStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new PaymentStateMachine();
    }

    // ============================================================
    // AC-state-1: VALID transitions canTransition returns true
    // ============================================================

    @Test
    void acState1a_initiatedToPending_isValid() {
        assertThat(stateMachine.canTransition(PaymentStatus.INITIATED, PaymentStatus.PENDING))
                .as("AC-state-1a: INITIATED->PENDING must be valid")
                .isTrue();
    }

    @Test
    void acState1b_initiatedToFailed_isValid() {
        assertThat(stateMachine.canTransition(PaymentStatus.INITIATED, PaymentStatus.FAILED))
                .as("AC-state-1b: INITIATED->FAILED must be valid")
                .isTrue();
    }

    @Test
    void acState1c_pendingToSuccess_isValid() {
        assertThat(stateMachine.canTransition(PaymentStatus.PENDING, PaymentStatus.SUCCESS))
                .as("AC-state-1c: PENDING->SUCCESS must be valid")
                .isTrue();
    }

    @Test
    void acState1d_pendingToFailed_isValid() {
        assertThat(stateMachine.canTransition(PaymentStatus.PENDING, PaymentStatus.FAILED))
                .as("AC-state-1d: PENDING->FAILED must be valid")
                .isTrue();
    }

    @Test
    void acState1e_successToRefunded_isValid() {
        assertThat(stateMachine.canTransition(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED))
                .as("AC-state-1e: SUCCESS->REFUNDED must be valid")
                .isTrue();
    }

    // ============================================================
    // AC-state-2: INVALID transitions canTransition returns false
    // ============================================================

    @Test
    void acState2a_initiatedToSuccess_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.INITIATED, PaymentStatus.SUCCESS))
                .as("AC-state-2a: INITIATED->SUCCESS must be INVALID")
                .isFalse();
    }

    @Test
    void acState2b_initiatedToRefunded_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.INITIATED, PaymentStatus.REFUNDED))
                .as("AC-state-2b: INITIATED->REFUNDED must be INVALID")
                .isFalse();
    }

    @Test
    void acState2c_successToPending_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.SUCCESS, PaymentStatus.PENDING))
                .as("AC-state-2c: SUCCESS->PENDING must be INVALID")
                .isFalse();
    }

    @Test
    void acState2d_pendingToInitiated_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.PENDING, PaymentStatus.INITIATED))
                .as("AC-state-2d: PENDING->INITIATED must be INVALID (no backward)")
                .isFalse();
    }

    @Test
    void acState2e_failedToSuccess_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.FAILED, PaymentStatus.SUCCESS))
                .as("AC-state-2e: FAILED->SUCCESS must be INVALID (terminal state)")
                .isFalse();
    }

    @Test
    void acState2f_failedToPending_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.FAILED, PaymentStatus.PENDING))
                .as("AC-state-2f: FAILED->PENDING must be INVALID (terminal state)")
                .isFalse();
    }

    @Test
    void acState2g_refundedToAny_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.REFUNDED, PaymentStatus.SUCCESS))
                .as("AC-state-2g: REFUNDED->SUCCESS must be INVALID (terminal)")
                .isFalse();
        assertThat(stateMachine.canTransition(PaymentStatus.REFUNDED, PaymentStatus.FAILED))
                .as("AC-state-2g: REFUNDED->FAILED must be INVALID (terminal)")
                .isFalse();
        assertThat(stateMachine.canTransition(PaymentStatus.REFUNDED, PaymentStatus.PENDING))
                .as("AC-state-2g: REFUNDED->PENDING must be INVALID (terminal)")
                .isFalse();
    }

    @Test
    void acState2h_successToFailed_isInvalid() {
        assertThat(stateMachine.canTransition(PaymentStatus.SUCCESS, PaymentStatus.FAILED))
                .as("AC-state-2h: SUCCESS->FAILED must be INVALID (point of no return)")
                .isFalse();
    }

    // ============================================================
    // AC-state-3: assertTransition on invalid -> ApiException INVALID_STATE_TRANSITION 422
    // ============================================================

    @Test
    void acState3a_assertTransition_initiatedToSuccess_throwsApiException422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> stateMachine.assertTransition(PaymentStatus.INITIATED, PaymentStatus.SUCCESS),
                "AC-state-3a: assertTransition INITIATED->SUCCESS must throw ApiException");

        assertThat(ex.getErrorCode())
                .as("AC-state-3a: error code must be INVALID_STATE_TRANSITION")
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(ex.getStatus())
                .as("AC-state-3a: HTTP status must be 422 UNPROCESSABLE_ENTITY")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getMessage())
                .as("AC-state-3a: message must describe the bad transition")
                .contains("INITIATED")
                .contains("SUCCESS");
    }

    @Test
    void acState3b_assertTransition_successToPending_throwsApiException422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> stateMachine.assertTransition(PaymentStatus.SUCCESS, PaymentStatus.PENDING),
                "AC-state-3b: assertTransition SUCCESS->PENDING must throw ApiException");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void acState3c_assertTransition_pendingToInitiated_throwsApiException422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> stateMachine.assertTransition(PaymentStatus.PENDING, PaymentStatus.INITIATED),
                "AC-state-3c: assertTransition PENDING->INITIATED must throw ApiException");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void acState3d_assertTransition_failedToSuccess_throwsApiException422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> stateMachine.assertTransition(PaymentStatus.FAILED, PaymentStatus.SUCCESS),
                "AC-state-3d: assertTransition FAILED->SUCCESS must throw ApiException");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ============================================================
    // AC-state-4: assertTransition on valid -> does NOT throw
    // ============================================================

    @Test
    void acState4a_assertTransition_initiatedToPending_doesNotThrow() {
        assertDoesNotThrow(
                () -> stateMachine.assertTransition(PaymentStatus.INITIATED, PaymentStatus.PENDING),
                "AC-state-4a: assertTransition INITIATED->PENDING must not throw");
    }

    @Test
    void acState4b_assertTransition_pendingToSuccess_doesNotThrow() {
        assertDoesNotThrow(
                () -> stateMachine.assertTransition(PaymentStatus.PENDING, PaymentStatus.SUCCESS),
                "AC-state-4b: assertTransition PENDING->SUCCESS must not throw");
    }

    @Test
    void acState4c_assertTransition_pendingToFailed_doesNotThrow() {
        assertDoesNotThrow(
                () -> stateMachine.assertTransition(PaymentStatus.PENDING, PaymentStatus.FAILED),
                "AC-state-4c: assertTransition PENDING->FAILED must not throw");
    }

    @Test
    void acState4d_assertTransition_initiatedToFailed_doesNotThrow() {
        assertDoesNotThrow(
                () -> stateMachine.assertTransition(PaymentStatus.INITIATED, PaymentStatus.FAILED),
                "AC-state-4d: assertTransition INITIATED->FAILED must not throw");
    }

    @Test
    void acState4e_assertTransition_successToRefunded_doesNotThrow() {
        assertDoesNotThrow(
                () -> stateMachine.assertTransition(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED),
                "AC-state-4e: assertTransition SUCCESS->REFUNDED must not throw");
    }
}
