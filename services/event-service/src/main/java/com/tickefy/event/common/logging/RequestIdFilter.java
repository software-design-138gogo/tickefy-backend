package com.tickefy.event.common.logging;

import com.tickefy.event.common.constants.HeaderConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HeaderConstants.X_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(HeaderConstants.REQUEST_ID, requestId);
        request.setAttribute(HeaderConstants.REQUEST_ID, requestId);
        response.setHeader(HeaderConstants.X_REQUEST_ID, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(HeaderConstants.REQUEST_ID);
        }
    }
}
