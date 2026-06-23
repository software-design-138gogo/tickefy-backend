package com.tickefy.csvingestion.modules.csvimport.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.csvingestion.common.constants.HeaderConstants;
import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import com.tickefy.csvingestion.common.response.ApiResponse;
import com.tickefy.csvingestion.common.response.ErrorResponse;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtVerifier jwtVerifier, ObjectMapper objectMapper) {
        this.jwtVerifier = jwtVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        Claims claims;
        try {
            claims = jwtVerifier.verify(token);
        } catch (ApiException e) {
            writeError(response, e.getStatus(), e.getErrorCode(), e.getMessage());
            return;
        }

        String userId = claims.getSubject();
        List<?> rawRoles = claims.get("roles", List.class);
        List<SimpleGrantedAuthority> authorities = rawRoles == null
                ? List.of()
                : rawRoles.stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .collect(Collectors.toList());

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private void writeError(
            HttpServletResponse response, HttpStatus status, ErrorCode errorCode, String message)
            throws IOException {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        ErrorResponse errorResponse = new ErrorResponse(errorCode.name(), message, null);
        ApiResponse<Void> body = ApiResponse.error(errorResponse, requestId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
