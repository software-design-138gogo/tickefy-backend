package com.tickefy.payment.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.common.constants.HeaderConstants;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for InternalTokenFilter — path isolation + fail-closed shared-secret on /internal/**.
 * Plain filter (no Spring context); uses spring-test mock servlet objects.
 */
@Tag("unit")
class InternalTokenFilterTest {

    private static final String TOKEN = "s3cr3t-internal-token-0123456789abcdef0123456789abcdef";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        req.setMethod("POST");
        req.setAttribute(HeaderConstants.REQUEST_ID, "req-test");
        return req;
    }

    private boolean chainProceeded(MockFilterChain chain) {
        return chain.getRequest() != null;
    }

    // ── /internal/** : token đúng → pass ──────────────────────────────────────
    @Test
    void internal_validToken_passesThrough() throws ServletException, IOException {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN, MAPPER);
        MockHttpServletRequest req = request("/internal/payments/refund");
        req.addHeader("X-Internal-Token", TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chainProceeded(chain)).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    // ── /internal/** : token sai → 401 ────────────────────────────────────────
    @Test
    void internal_wrongToken_401() throws ServletException, IOException {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN, MAPPER);
        MockHttpServletRequest req = request("/internal/payments");
        req.addHeader("X-Internal-Token", "WRONG");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chainProceeded(chain)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("UNAUTHORIZED").doesNotContain(TOKEN);
    }

    // ── /internal/** : thiếu header → 401 ─────────────────────────────────────
    @Test
    void internal_missingHeader_401() throws ServletException, IOException {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN, MAPPER);
        MockHttpServletRequest req = request("/internal/payments/refund");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chainProceeded(chain)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    // ── /internal/** : config token rỗng → 401 (FAIL-CLOSED) ──────────────────
    @Test
    void internal_emptyConfigToken_401_failClosed() throws ServletException, IOException {
        InternalTokenFilter filter = new InternalTokenFilter("", MAPPER);
        MockHttpServletRequest req = request("/internal/payments");
        req.addHeader("X-Internal-Token", "anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chainProceeded(chain)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    // ── /api/payments/callback : public webhook → KHÔNG bị chặn (pass dù không token) ──
    @Test
    void callback_noToken_passesThrough() throws ServletException, IOException {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN, MAPPER);
        MockHttpServletRequest req = request("/api/payments/callback");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chainProceeded(chain)).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    // ── /actuator : pass through ──────────────────────────────────────────────
    @Test
    void actuator_noToken_passesThrough() throws ServletException, IOException {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN, MAPPER);
        MockHttpServletRequest req = request("/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chainProceeded(chain)).isTrue();
    }
}
