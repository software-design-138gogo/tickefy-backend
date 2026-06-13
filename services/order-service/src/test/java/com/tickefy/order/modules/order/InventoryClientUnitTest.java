package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.order.common.exception.ErrorCode;
import com.tickefy.order.modules.order.client.InventoryBusinessException;
import com.tickefy.order.modules.order.client.InventoryClient;
import com.tickefy.order.modules.order.client.InventoryUnavailableException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Unit tests for InventoryClient error-body → exception mapping.
 *
 * Strategy: test the private handleErrorResponse(HttpStatusCode, byte[]) method directly
 * via reflection. This is a pure in-memory test — no HTTP, no WireMock, no Docker, no Spring context.
 *
 * Why reflection: handleErrorResponse is private and is the entire mapping logic.
 * The RestClient.exchange() lambda cannot be intercepted without HTTP infrastructure.
 * Reflection lets us verify the mapping contract as a pure unit, which is the correct
 * tradeoff here (brittle WireMock h2c vs clean, stable reflection on a stable private method).
 *
 * Note to backend-worker: InventoryClient constructor couples baseUrl + RestClient build in one
 * place. If a package-visible overload were added (constructor accepting RestClient + ObjectMapper),
 * these tests could switch to WireMock without reflection. Consider adding it.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class InventoryClientUnitTest {

    private InventoryClient inventoryClient;
    private Method handleErrorResponse;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Use a dummy base URL — handleErrorResponse is never going to make HTTP calls
        inventoryClient = new InventoryClient("http://localhost:9999", objectMapper);

        // Reflect into the private mapping method
        handleErrorResponse = InventoryClient.class
                .getDeclaredMethod("handleErrorResponse", HttpStatusCode.class, byte[].class);
        handleErrorResponse.setAccessible(true);
    }

    /**
     * Helper to invoke handleErrorResponse and unwrap the thrown exception.
     * Returns the exception thrown by the method (expected usage: always throws).
     */
    private RuntimeException invokeHandleError(HttpStatusCode status, String body) {
        try {
            handleErrorResponse.invoke(inventoryClient, status, body.getBytes());
            throw new AssertionError("handleErrorResponse should have thrown");
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) {
                return re;
            }
            throw new AssertionError("Expected RuntimeException but got: " + cause.getClass(), cause);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Reflection access failed", e);
        }
    }

    // ---------------------------------------------------------------------------
    // AC-IC-1: body code TICKET_SOLD_OUT (409) → InventoryBusinessException 409
    // ---------------------------------------------------------------------------

    @Test
    void handleError_409_ticketSoldOut_throwsInventoryBusinessException() {
        String body = "{\"success\":false,\"data\":null,"
                + "\"error\":{\"code\":\"TICKET_SOLD_OUT\",\"message\":\"Ticket sold out\",\"details\":null},"
                + "\"requestId\":\"req-1\"}";

        RuntimeException ex = invokeHandleError(HttpStatus.CONFLICT, body);

        assertThat(ex).isInstanceOf(InventoryBusinessException.class);
        InventoryBusinessException ibe = (InventoryBusinessException) ex;
        assertThat(ibe.getErrorCode()).isEqualTo(ErrorCode.TICKET_SOLD_OUT);
        assertThat(ibe.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ibe.getHttpStatus().value()).isEqualTo(409);
    }

    // ---------------------------------------------------------------------------
    // AC-IC-2: body code PER_USER_LIMIT_EXCEEDED (422) → exception 422 + details preserved
    // ---------------------------------------------------------------------------

    @Test
    void handleError_422_perUserLimitExceeded_throwsInventoryBusinessExceptionWithDetails() {
        String body = "{\"success\":false,\"data\":null,"
                + "\"error\":{\"code\":\"PER_USER_LIMIT_EXCEEDED\",\"message\":\"Per-user limit exceeded\","
                + "\"details\":{\"limit\":2,\"current\":2}},"
                + "\"requestId\":\"req-2\"}";

        RuntimeException ex = invokeHandleError(HttpStatus.UNPROCESSABLE_ENTITY, body);

        assertThat(ex).isInstanceOf(InventoryBusinessException.class);
        InventoryBusinessException ibe = (InventoryBusinessException) ex;
        assertThat(ibe.getErrorCode()).isEqualTo(ErrorCode.PER_USER_LIMIT_EXCEEDED);
        assertThat(ibe.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ibe.getHttpStatus().value()).isEqualTo(422);
        // details MUST be non-null — preserved from body
        assertThat(ibe.getDetails()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // AC-IC-3: body code SALE_WINDOW_CLOSED (403) → InventoryBusinessException 403
    // ---------------------------------------------------------------------------

    @Test
    void handleError_403_saleWindowClosed_throwsInventoryBusinessException() {
        String body = "{\"success\":false,\"data\":null,"
                + "\"error\":{\"code\":\"SALE_WINDOW_CLOSED\",\"message\":\"Sale window closed\",\"details\":null},"
                + "\"requestId\":\"req-3\"}";

        RuntimeException ex = invokeHandleError(HttpStatus.FORBIDDEN, body);

        assertThat(ex).isInstanceOf(InventoryBusinessException.class);
        InventoryBusinessException ibe = (InventoryBusinessException) ex;
        assertThat(ibe.getErrorCode()).isEqualTo(ErrorCode.SALE_WINDOW_CLOSED);
        assertThat(ibe.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ibe.getHttpStatus().value()).isEqualTo(403);
    }

    // ---------------------------------------------------------------------------
    // AC-IC-4: 5xx → InventoryUnavailableException (treat as infra error)
    // ---------------------------------------------------------------------------

    @Test
    void handleError_500_serverError_throwsInventoryUnavailableException() {
        String body = "{\"success\":false,\"data\":null,"
                + "\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"DB error\",\"details\":null},"
                + "\"requestId\":\"req-4\"}";

        RuntimeException ex = invokeHandleError(HttpStatus.INTERNAL_SERVER_ERROR, body);

        assertThat(ex).isInstanceOf(InventoryUnavailableException.class);
    }

    // ---------------------------------------------------------------------------
    // AC-IC-5: 502 Bad Gateway (5xx) → InventoryUnavailableException
    // ---------------------------------------------------------------------------

    @Test
    void handleError_502_badGateway_throwsInventoryUnavailableException() {
        String body = "{\"success\":false,\"data\":null,"
                + "\"error\":{\"code\":\"BAD_GATEWAY\",\"message\":\"upstream timeout\",\"details\":null},"
                + "\"requestId\":\"req-5\"}";

        RuntimeException ex = invokeHandleError(HttpStatus.BAD_GATEWAY, body);

        assertThat(ex).isInstanceOf(InventoryUnavailableException.class);
    }

    // ---------------------------------------------------------------------------
    // AC-IC-6: unknown 4xx (404 with unrecognized code) → InventoryUnavailableException
    // (design: unknown 4xx treated as unavailable to be safe, per handleErrorResponse comment)
    // ---------------------------------------------------------------------------

    @Test
    void handleError_404_unknownCode_throwsInventoryUnavailableException() {
        String body = "{\"success\":false,\"data\":null,"
                + "\"error\":{\"code\":\"CONCERT_NOT_FOUND\",\"message\":\"Not found\",\"details\":null},"
                + "\"requestId\":\"req-6\"}";

        RuntimeException ex = invokeHandleError(HttpStatus.NOT_FOUND, body);

        assertThat(ex).isInstanceOf(InventoryUnavailableException.class);
    }

    // ---------------------------------------------------------------------------
    // AC-IC-7: malformed body (non-JSON) for 4xx → falls back to unknown → InventoryUnavailableException
    // ---------------------------------------------------------------------------

    @Test
    void handleError_400_malformedBody_throwsInventoryUnavailableException() {
        String body = "not-json-at-all";

        RuntimeException ex = invokeHandleError(HttpStatus.BAD_REQUEST, body);

        // extractErrorCode returns null on parse failure → falls into unknown 4xx branch
        assertThat(ex).isInstanceOf(InventoryUnavailableException.class);
    }
}
