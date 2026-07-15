package com.tickefy.payment.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.common.constants.HeaderConstants;
import com.tickefy.payment.common.response.ApiResponse;
import com.tickefy.payment.common.response.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the internal service-to-service surface (`/internal/**`) with a shared secret
 * (header {@code X-Internal-Token}). Mirrors RequestIdFilter (plain OncePerRequestFilter, registered
 * via FilterRegistrationBean in WebConfig — NO spring-security starter).
 *
 * <p>Scope: ONLY paths starting with {@code /internal/}. Public webhook ({@code /api/payments/callback}),
 * dev endpoints ({@code /dev/**}) and actuator pass through untouched.
 *
 * <p>Fail-CLOSED: an empty configured token, a missing header, or a mismatch all yield 401 — there is
 * no dev bypass (this is an internal trust boundary; callers MUST present the token). The token is
 * compared with {@link MessageDigest#isEqual} (length-leak negligible for a fixed 256-bit secret; not
 * a claim of absolute constant-time). The 401 body uses the shared error envelope and never echoes the
 * token.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final String configToken;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(String configToken, ObjectMapper objectMapper) {
        this.configToken = configToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Only guard the internal surface; everything else (callback/dev/actuator/...) passes through.
        if (!request.getRequestURI().startsWith(INTERNAL_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isAuthorized(request)) {
            writeUnauthorized(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthorized(HttpServletRequest request) {
        // Fail-closed: no configured token => reject (no bypass).
        if (configToken == null || configToken.isEmpty()) {
            log.warn("Internal token not configured (app.internal.token empty) — rejecting {} {}",
                    request.getMethod(), request.getRequestURI());
            return false;
        }
        String provided = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                configToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        ErrorResponse error =
                new ErrorResponse("UNAUTHORIZED", "Invalid or missing internal token", null);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(error, requestId)));
        log.warn("Rejected internal request {} {} — invalid/missing internal token",
                request.getMethod(), request.getRequestURI());
    }
}
