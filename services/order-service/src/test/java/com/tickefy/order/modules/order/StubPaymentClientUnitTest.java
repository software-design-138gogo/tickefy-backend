package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.order.modules.order.client.CreatePaymentCommand;
import com.tickefy.order.modules.order.client.PaymentResult;
import com.tickefy.order.modules.order.client.StubPaymentClient;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AC-stub-regress: regression test for StubPaymentClient.
 * Verifies the new signature createTransaction(CreatePaymentCommand, bearerToken)
 * is honoured and the stub returns a valid PaymentResult with status=INITIATED.
 */
@Tag("unit")
class StubPaymentClientUnitTest {

    // -----------------------------------------------------------------------
    // AC-stub-regress: new signature accepted, returns INITIATED + non-null transactionId
    // -----------------------------------------------------------------------

    @Test
    void acStubRegress_newSignature_returnsInitiatedResultWithNonNullTransactionIdAndPaymentUrl() {
        StubPaymentClient stub = new StubPaymentClient();

        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CreatePaymentCommand cmd = new CreatePaymentCommand(orderId, userId, 100000L, "VND", "order-" + orderId);

        PaymentResult result = stub.createTransaction(cmd, "any-bearer-token");

        assertThat(result).isNotNull();
        assertThat(result.status())
                .as("StubPaymentClient must return status=INITIATED")
                .isEqualTo("INITIATED");
        assertThat(result.transactionId())
                .as("transactionId must be non-null and non-empty")
                .isNotNull()
                .isNotEmpty();
        assertThat(result.paymentUrl())
                .as("paymentUrl must be non-null and non-empty stub URL")
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void acStubRegress_bearerTokenIgnored_doesNotThrow() {
        StubPaymentClient stub = new StubPaymentClient();

        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CreatePaymentCommand cmd = new CreatePaymentCommand(orderId, userId, 50000L, "VND", "order-" + orderId);

        // bearerToken is ignored by stub — must not throw regardless of value
        PaymentResult resultNull = stub.createTransaction(cmd, null);
        PaymentResult resultEmpty = stub.createTransaction(cmd, "");
        PaymentResult resultValid = stub.createTransaction(cmd, "real-token");

        assertThat(resultNull.status()).isEqualTo("INITIATED");
        assertThat(resultEmpty.status()).isEqualTo("INITIATED");
        assertThat(resultValid.status()).isEqualTo("INITIATED");
    }
}
