package com.tickefy.inventory.common.logging;

import com.tickefy.inventory.common.constants.HeaderConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            String requestId = MDC.get(HeaderConstants.REQUEST_ID);
            log.info(
                    "method={} path={} status={} durationMs={} requestId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs,
                    requestId);
        }
    }
}
