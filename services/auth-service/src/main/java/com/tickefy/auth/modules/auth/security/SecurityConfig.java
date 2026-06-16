package com.tickefy.auth.modules.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.auth.common.constants.HeaderConstants;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.common.response.ApiResponse;
import com.tickefy.auth.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, CookieProperties.class})
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/register",
                                "/auth/login",
                                "/auth/refresh-token",
                                "/actuator/**",
                                "/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/auth/logout").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::handleUnauthorized)
                        .accessDeniedHandler(this::handleForbidden));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    /**
     * CORS for credentialed (cookie) requests from the FE.
     *
     * <p>Allowed origins come from {@code app.cors.allowed-origins} (CSV). When the property is
     * blank — the prod default, since per api-contracts §1 the gateway owns CORS — no mapping is
     * registered and CORS stays off. {@code allowCredentials(true)} is mandatory for cookies and
     * is incompatible with a wildcard origin, so origins are always listed explicitly.
     *
     * <p>TODO: once api-gateway exists, the gateway must own CORS + credentialed-cookie passthrough.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return source; // no registered config → CORS effectively off (gateway owns it in prod)
        }
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins); // explicit list — never "*" (incompatible with credentials)
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void handleUnauthorized(
            HttpServletRequest request, HttpServletResponse response, org.springframework.security.core.AuthenticationException authException)
            throws IOException {
        writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication required");
    }

    private void handleForbidden(
            HttpServletRequest request, HttpServletResponse response, org.springframework.security.access.AccessDeniedException accessDeniedException)
            throws IOException {
        writeError(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode errorCode, String message)
            throws IOException {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        ErrorResponse errorResponse = new ErrorResponse(errorCode.name(), message, null);
        ApiResponse<Void> body = ApiResponse.error(errorResponse, requestId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
