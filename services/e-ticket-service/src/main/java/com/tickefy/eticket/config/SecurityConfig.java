package com.tickefy.eticket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.eticket.common.constants.HeaderConstants;
import com.tickefy.eticket.common.exception.ErrorCode;
import com.tickefy.eticket.common.response.ApiResponse;
import com.tickefy.eticket.common.response.ErrorResponse;
import com.tickefy.eticket.common.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/tickets/issue").hasAnyRole("ADMIN", "ORGANIZER")
                        .requestMatchers("/internal/tickets/**").hasAnyRole("CHECKIN_STAFF", "STAFF", "ADMIN")
                        .requestMatchers("/api/tickets/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> writeSecurityError(
                                response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((request, response, ex) -> writeSecurityError(
                                response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("JWT authentication only");
        };
    }

    private void writeSecurityError(HttpServletResponse response, HttpStatus status, ErrorCode code, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var body = ApiResponse.error(new ErrorResponse(code.name(), message, null), MDC.get(HeaderConstants.REQUEST_ID));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
