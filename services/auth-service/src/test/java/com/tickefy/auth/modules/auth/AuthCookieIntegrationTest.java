package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.auth.BaseIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Integration tests for the HttpOnly cookie auth strategy (PLAN-auth-cookie §6).
 * Reuses singleton Testcontainers Postgres + Redis. Backward compatibility: body tokens are
 * still asserted present alongside the new Set-Cookie headers.
 *
 * <p>Test profile sets {@code app.cookie.secure=false}, {@code app.cookie.same-site=Lax}.
 */
class AuthCookieIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanBlacklist() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // --------------------------------------------------------------------- helpers

    private String uniqueEmail() {
        return "cookie+" + System.nanoTime() + "@tickefy.com";
    }

    private void register(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.postForEntity(
                "/auth/register",
                new HttpEntity<>(Map.of("email", email, "password", password, "fullName", "Cookie User"), headers),
                String.class);
    }

    private ResponseEntity<String> login(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                "/auth/login", new HttpEntity<>(Map.of("email", email, "password", password), headers), String.class);
    }

    /** Returns the raw Set-Cookie line whose name matches, or null. */
    private String setCookie(ResponseEntity<String> resp, String name) {
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        return cookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }

    /** Extracts the cookie value (between {@code name=} and the first {@code ;}). */
    private String cookieValue(String setCookieLine, String name) {
        String afterName = setCookieLine.substring((name + "=").length());
        int semi = afterName.indexOf(';');
        return semi >= 0 ? afterName.substring(0, semi) : afterName;
    }

    private JsonNode data(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private String errorCode(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).path("error").path("code").asText();
    }

    // --------------------------------------------------------------------- tests

    @Test
    @DisplayName("login: body keeps tokens AND Set-Cookie has HttpOnly access(/) + refresh(/auth)")
    void login_setsHttpOnlyCookies_andKeepsBodyTokens() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!");

        ResponseEntity<String> resp = login(email, "Password1!");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Backward compat: body still carries both tokens
        assertThat(data(resp).path("accessToken").asText()).isNotBlank();
        assertThat(data(resp).path("refreshToken").asText()).isNotBlank();

        String accessCookie = setCookie(resp, "access_token");
        String refreshCookie = setCookie(resp, "refresh_token");
        assertThat(accessCookie).isNotNull();
        assertThat(refreshCookie).isNotNull();

        // access cookie: HttpOnly, Path=/, Max-Age 900, SameSite Lax, NOT Secure (test profile)
        assertThat(accessCookie).contains("HttpOnly");
        assertThat(accessCookie).contains("Path=/");
        assertThat(accessCookie).contains("Max-Age=900");
        assertThat(accessCookie).contains("SameSite=Lax");
        assertThat(accessCookie).doesNotContain("Secure");

        // refresh cookie: HttpOnly, Path=/auth, Max-Age 604800
        assertThat(refreshCookie).contains("HttpOnly");
        assertThat(refreshCookie).contains("Path=/auth");
        assertThat(refreshCookie).contains("Max-Age=604800");
    }

    @Test
    @DisplayName("refresh via cookie only (empty body): 200 + new access + Set-Cookie access")
    void refresh_viaCookie_returnsNewAccessAndCookie() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!");
        String refreshCookie = setCookie(login(email, "Password1!"), "refresh_token");
        String refreshValue = cookieValue(refreshCookie, "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshValue);
        // No body at all — proves @NotBlank removal + cookie-first read
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/refresh-token", new HttpEntity<>(null, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(resp).path("accessToken").asText()).isNotBlank();
        assertThat(setCookie(resp, "access_token")).isNotNull();
    }

    @Test
    @DisplayName("refresh via body fallback (no cookie): still 200 (backward compatible)")
    void refresh_viaBodyFallback_stillWorks() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!");
        String refreshTokenBody = data(login(email, "Password1!")).path("refreshToken").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/refresh-token",
                new HttpEntity<>(Map.of("refreshToken", refreshTokenBody), headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(resp).path("accessToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("refresh: neither cookie nor body → 401 INVALID_TOKEN")
    void refresh_noTokenAnywhere_returns401() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/refresh-token", new HttpEntity<>(null, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(resp)).isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("refresh: unknown cookie token → 401 INVALID_TOKEN (error map preserved)")
    void refresh_unknownCookieToken_returns401InvalidToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=totally-unknown-token");
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/refresh-token", new HttpEntity<>(null, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(resp)).isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("logout: clears both cookies (Max-Age=0) + blacklists token")
    void logout_clearsCookies() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!");
        String accessToken = data(login(email, "Password1!")).path("accessToken").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/logout", new HttpEntity<>(null, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String clearAccess = setCookie(resp, "access_token");
        String clearRefresh = setCookie(resp, "refresh_token");
        assertThat(clearAccess).contains("Max-Age=0");
        assertThat(clearRefresh).contains("Max-Age=0");

        // Reusing the access token now must be rejected
        ResponseEntity<String> reuse = restTemplate.postForEntity(
                "/auth/logout", new HttpEntity<>(null, headers), String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(reuse)).isEqualTo("TOKEN_REVOKED");
    }

}
