package com.tickefy.event.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.common.response.ApiResponse;
import com.tickefy.event.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/concerts",
                                                "/concerts/**",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        .requestMatchers("/admin/**")
                                        .hasAnyRole("ORGANIZER", "ADMIN")
                                        .requestMatchers("/internal/**")
                                        .authenticated()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        exceptions ->
                                exceptions.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeError(
                                                                objectMapper,
                                                                response,
                                                                401,
                                                                "INVALID_TOKEN",
                                                                "Invalid session.",
                                                                request.getAttribute("requestId")))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        writeError(
                                                                objectMapper,
                                                                response,
                                                                403,
                                                                "FORBIDDEN",
                                                                "Insufficient permissions.",
                                                                request.getAttribute("requestId"))))
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(
                                                jwt ->
                                                        jwt.jwtAuthenticationConverter(
                                                                jwtAuthenticationConverter()))
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeError(
                                                                objectMapper,
                                                                response,
                                                                401,
                                                                "INVALID_TOKEN",
                                                                "Invalid session.",
                                                                request.getAttribute("requestId"))))
                .cors(Customizer.withDefaults())
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::authorities);
        return converter;
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(String::toUpperCase)
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    private static void writeError(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            int status,
            String code,
            String message,
            Object requestId)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.error(
                        new ErrorResponse(status, code, message, java.util.Map.of()),
                        requestId != null ? requestId.toString() : null));
    }
}
