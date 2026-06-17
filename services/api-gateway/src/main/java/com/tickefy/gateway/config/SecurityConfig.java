package com.tickefy.gateway.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http) {
    return http
        // Gateway using Bearer JWT, not using browser form session.
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)

        .authorizeExchange(exchange -> exchange
            // CORS preflight. Specific CORS policy to be done later.
            .pathMatchers(HttpMethod.OPTIONS, "/**")
            .permitAll()

            // Gateway health endpoint.
            .pathMatchers(
                "/actuator/health",
                "/actuator/health/**")
            .permitAll()

            // Auth public endpoints.
            .pathMatchers(
                HttpMethod.POST,
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/refresh-token")
            .permitAll()

            // Public concert browsing.
            .pathMatchers(
                HttpMethod.GET,
                "/api/concerts",
                "/api/concerts/**")
            .permitAll()

            // Public ticket types and availability reads.
            .pathMatchers(
                HttpMethod.GET,
                "/api/inventory/**")
            .permitAll()

            // Payment provider callback.
            .pathMatchers(
                HttpMethod.POST,
                "/api/payments/callback")
            .permitAll()

            // Everything else requires a valid JWT.
            .anyExchange()
            .authenticated())

        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(withDefaults()))

        .build();
  }
}