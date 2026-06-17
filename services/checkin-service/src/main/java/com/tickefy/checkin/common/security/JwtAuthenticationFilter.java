package com.tickefy.checkin.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.common.constants.HeaderConstants;
import com.tickefy.checkin.common.exception.ErrorCode;
import com.tickefy.checkin.common.response.ApiResponse;
import com.tickefy.checkin.common.response.ErrorResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            var claims = jwtVerifier.parseAndValidate(authHeader.substring(7));
            String userId = claims.getSubject();
            List<SimpleGrantedAuthority> authorities = authoritiesFromClaims(claims);
            if (userId != null && !authorities.isEmpty()) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException ex) {
            writeAuthError(response, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_TOKEN, "Token is invalid or expired");
        } catch (JwtException | IllegalArgumentException ex) {
            writeAuthError(response, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_TOKEN, "Token is invalid");
        }
    }

    private void writeAuthError(HttpServletResponse response, HttpStatus status, ErrorCode code, String message)
            throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var body = ApiResponse.error(new ErrorResponse(code.name(), message, null), MDC.get(HeaderConstants.REQUEST_ID));
        objectMapper.writeValue(response.getWriter(), body);
    }

    private List<SimpleGrantedAuthority> authoritiesFromClaims(Claims claims) {
        List<String> roles = new ArrayList<>();
        addCollectionClaim(roles, claims.get("roles"));
        addCollectionClaim(roles, claims.get("authorities"));
        String legacyRole = claims.get("role", String.class);
        if (legacyRole != null && !legacyRole.isBlank()) {
            roles.add(legacyRole);
        }
        String scope = claims.get("scope", String.class);
        if (scope != null && !scope.isBlank()) {
            roles.addAll(List.of(scope.split("\\s+")));
        }
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(this::normalizeRole)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    private void addCollectionClaim(List<String> roles, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(roles::add);
        }
    }

    private String normalizeRole(String role) {
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            return normalized;
        }
        return "ROLE_" + normalized;
    }
}
