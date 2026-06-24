package com.tickefy.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Spring Security configuration for the notification-service.
 *
 * <p>Authentication strategy: OAuth2 Resource Server with RS256 JWT validation.
 * The public key is loaded from the {@code JWT_PUBLIC_KEY_PATH} environment variable
 * (PEM file path) or from the {@code spring.security.oauth2.resourceserver.jwt.public-key-location}
 * property.
 *
 * <p>Public paths (no auth required):
 * <ul>
 *   <li>{@code /actuator/health} — health probe
 *   <li>{@code /health} — simple health alias
 *   <li>{@code /swagger-ui/**}, {@code /v3/api-docs/**} — OpenAPI
 * </ul>
 *
 * <p>All other paths require a valid Bearer JWT.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Notification service is stateless — disable sessions and CSRF
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)

            .authorizeHttpRequests(auth -> auth
                // Public health-check and documentation endpoints
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info",
                    "/health",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs",
                    "/v3/api-docs/**"
                ).permitAll()
                // All other endpoints require authentication
                .anyRequest().authenticated()
            )

            // Use JWT bearer token for authentication (RS256 via public key)
            // Spring Boot auto-configures JwtDecoder from spring.security.oauth2.resourceserver.jwt.public-key-location
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(org.springframework.security.config.Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Converter that extracts the {@code sub} claim as the principal name.
     *
     * <p>Downstream controllers can inject {@code @AuthenticationPrincipal Jwt jwt}
     * and call {@code jwt.getSubject()} to get the userId (UUID string).
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter =
            new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
