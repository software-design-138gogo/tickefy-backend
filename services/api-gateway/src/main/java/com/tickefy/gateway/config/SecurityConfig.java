package com.tickefy.gateway.config;

import static org.springframework.security.config.Customizer.withDefaults;

import com.tickefy.gateway.error.GatewayAccessDeniedHandler;
import com.tickefy.gateway.error.GatewayAuthenticationEntryPoint;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      UrlBasedCorsConfigurationSource corsConfigurationSource,
      GatewayAuthenticationEntryPoint authenticationEntryPoint,
      GatewayAccessDeniedHandler accessDeniedHandler) {
    return http
        .cors(cors -> cors.configurationSource(
            corsConfigurationSource))

        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)

        .exceptionHandling(exceptionHandling -> exceptionHandling
            .authenticationEntryPoint(
                authenticationEntryPoint)
            .accessDeniedHandler(
                accessDeniedHandler))

        .authorizeExchange(exchange -> exchange
            .pathMatchers(
                HttpMethod.OPTIONS,
                "/**")
            .permitAll()

            // Gateway health endpoint.
            .pathMatchers(
                "/actuator/health",
                "/actuator/health/**",
                "/livez",
                "/readyz"
            )
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
            .jwt(withDefaults())
            .authenticationEntryPoint(
                authenticationEntryPoint)
            .accessDeniedHandler(
                accessDeniedHandler))

        .build();
  }
}