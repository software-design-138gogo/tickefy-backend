package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.auth.BaseIntegrationTest;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Integration tests mapping 1-1 to auth.md "Tieu chi chap nhan" §AC1-AC9 + §AC11.
 * Uses singleton Testcontainers Postgres + Redis from BaseIntegrationTest.
 * Bcrypt strength = 4 in test profile for speed.
 */
class AuthIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // Unique-per-test email to avoid cross-test DB collisions
    private String uniqueEmail() {
        return "test+" + System.nanoTime() + "@tickefy.com";
    }

    @BeforeEach
    void cleanBlacklist() {
        // Flush Redis between tests to avoid blacklist leakage
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ---------------------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------------------

    private ResponseEntity<String> register(String email, String password, String fullName) {
        Map<String, String> body = Map.of(
                "email", email,
                "password", password,
                "fullName", fullName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/auth/register", new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> login(String email, String password) {
        Map<String, String> body = Map.of("email", email, "password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> refresh(String refreshToken) {
        Map<String, String> body = Map.of("refreshToken", refreshToken);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                "/auth/refresh-token", new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> logout(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return restTemplate.postForEntity("/auth/logout", new HttpEntity<>(null, headers), String.class);
    }

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private String dataField(String json, String path) throws Exception {
        return parse(json).path("data").path(path).asText();
    }

    private String errorCode(String json) throws Exception {
        return parse(json).path("error").path("code").asText();
    }

    // ---------------------------------------------------------------------------
    // AC#1 — register_createsUser_withAudienceRole_andHashedPassword
    // auth.md: "Dang ky tao duoc user voi role mac dinh AUDIENCE, password duoc hash"
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#1 register: creates user with AUDIENCE role, password hashed bcrypt")
    void register_createsUser_withAudienceRole_andHashedPassword() throws Exception {
        String email = uniqueEmail();
        String plainPassword = "SecurePass1";

        ResponseEntity<String> resp = register(email, plainPassword, "Test User");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = parse(resp.getBody()).path("data");
        assertThat(data.path("email").asText()).isEqualTo(email);
        assertThat(data.path("roles").toString()).contains("AUDIENCE");
        // Response MUST NOT contain any password field
        assertThat(data.toString()).doesNotContain("password");
        assertThat(data.toString()).doesNotContain(plainPassword);

        // Verify DB: password_hash starts with $2 (bcrypt) and is NOT the plaintext
        String userId = data.path("userId").asText();
        userRepository.findById(java.util.UUID.fromString(userId)).ifPresent(u -> {
            assertThat(u.getPasswordHash()).startsWith("$2");
            assertThat(u.getPasswordHash()).isNotEqualTo(plainPassword);
            assertThat(u.getRoles()).extracting("code").contains("AUDIENCE");
        });
    }

    // ---------------------------------------------------------------------------
    // AC#2 — register_duplicateEmail_returns409_emailAlreadyExists
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#2 register duplicate email: 409 EMAIL_ALREADY_EXISTS")
    void register_duplicateEmail_returns409_emailAlreadyExists() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "First User");

        ResponseEntity<String> resp2 = register(email, "AnotherPass1", "Second User");

        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(resp2.getBody())).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    // ---------------------------------------------------------------------------
    // AC#3 — login_validCreds_returnsAccessAndRefresh
    // auth.md: "Dang nhap tra ve access token + refresh token hop le"
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#3 login valid creds: returns accessToken + refreshToken")
    void login_validCreds_returnsAccessAndRefresh() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Login User");

        ResponseEntity<String> resp = login(email, "Password1!");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = parse(resp.getBody()).path("data");
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).isNotBlank();
        assertThat(data.path("tokenType").asText()).isEqualToIgnoringCase("Bearer");
        assertThat(data.path("expiresIn").asLong()).isGreaterThan(0L);
    }

    // ---------------------------------------------------------------------------
    // AC#4 — login_wrongPassword_returns401_invalidCredentials
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#4 login wrong password: 401 INVALID_CREDENTIALS")
    void login_wrongPassword_returns401_invalidCredentials() throws Exception {
        String email = uniqueEmail();
        register(email, "CorrectPass1", "Wrong Pwd User");

        ResponseEntity<String> resp = login(email, "WrongPassword99");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(resp.getBody())).isEqualTo("INVALID_CREDENTIALS");
    }

    // ---------------------------------------------------------------------------
    // AC#5 — login_unknownEmail_returns401_invalidCredentials (anti-enumeration)
    // auth.md: "khong tiet lo email co ton tai hay khong (chong user enumeration)"
    // SAME body as wrong password — assert error.code identical
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#5 login unknown email: 401 INVALID_CREDENTIALS — same body as wrong password (no enumeration)")
    void login_unknownEmail_returns401_invalidCredentials_noEnumeration() throws Exception {
        String email = uniqueEmail();
        register(email, "CorrectPass1", "Enum User");

        // Wrong password response
        ResponseEntity<String> wrongPwd = login(email, "WrongPass1!");
        // Unknown email response
        ResponseEntity<String> unknownEmail = login("nobody@notexist.invalid", "SomePass1!");

        assertThat(wrongPwd.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // CRITICAL: both must return same error code — no info leakage
        assertThat(errorCode(wrongPwd.getBody())).isEqualTo("INVALID_CREDENTIALS");
        assertThat(errorCode(unknownEmail.getBody())).isEqualTo("INVALID_CREDENTIALS");
    }

    // ---------------------------------------------------------------------------
    // AC#6 — refresh_validToken_returnsNewAccess
    // auth.md: "Refresh token lam moi duoc access token khi con han"
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#6 refresh valid token: returns new access token")
    void refresh_validToken_returnsNewAccess() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Refresh User");
        String refreshToken = dataField(login(email, "Password1!").getBody(), "refreshToken");

        ResponseEntity<String> resp = refresh(refreshToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = parse(resp.getBody()).path("data");
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("tokenType").asText()).isEqualToIgnoringCase("Bearer");
        assertThat(data.path("expiresIn").asLong()).isGreaterThan(0L);
    }

    // ---------------------------------------------------------------------------
    // AC#7 — refresh_revokedToken_returns401_tokenRevoked
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#7 refresh revoked token: 401 TOKEN_REVOKED")
    void refresh_revokedToken_returns401_tokenRevoked() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Revoke User");
        JsonNode loginData = parse(login(email, "Password1!").getBody()).path("data");
        String accessToken = loginData.path("accessToken").asText();
        String refreshToken = loginData.path("refreshToken").asText();

        // logout revokes all refresh tokens for this user
        ResponseEntity<String> logoutResp = logout(accessToken);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Now attempt to refresh with the revoked token
        ResponseEntity<String> resp = refresh(refreshToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(resp.getBody())).isEqualTo("TOKEN_REVOKED");
    }

    // ---------------------------------------------------------------------------
    // AC#8 — refresh_unknownToken_returns401_invalidToken
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#8 refresh unknown token: 401 INVALID_TOKEN")
    void refresh_unknownToken_returns401_invalidToken() throws Exception {
        ResponseEntity<String> resp = refresh("totally-random-garbage-token-that-does-not-exist");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(resp.getBody())).isEqualTo("INVALID_TOKEN");
    }

    // ---------------------------------------------------------------------------
    // AC#9 — logout_thenProtectedRequest_returns401_tokenRevoked
    // auth.md: "Logout day token vao blacklist; token do bi tu choi o request tiep theo"
    // Protected endpoint used: /auth/logout itself (requires authentication)
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#9 logout then reuse access token: 401 TOKEN_REVOKED")
    void logout_thenProtectedRequest_returns401_tokenRevoked() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Logout User");
        String accessToken = dataField(login(email, "Password1!").getBody(), "accessToken");

        // First logout — should succeed
        ResponseEntity<String> firstLogout = logout(accessToken);
        assertThat(firstLogout.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second request with same access token — must be rejected
        ResponseEntity<String> secondLogout = logout(accessToken);
        assertThat(secondLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(secondLogout.getBody())).isEqualTo("TOKEN_REVOKED");
    }

    // ---------------------------------------------------------------------------
    // AC#10 — blacklist_keyHasTtl_equalToRemainingExp
    // auth.md: "Token trong blacklist tu bien mat khoi Redis khi het han (TTL)"
    // Verify: Redis key "blacklist:{jti}" has TTL > 0 and <= 900s after logout
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#10 blacklist TTL: Redis key has positive TTL <= 900s after logout")
    void blacklist_keyHasTtl_equalToRemainingExp() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "TTL User");
        String accessToken = dataField(login(email, "Password1!").getBody(), "accessToken");

        // Parse jti from JWT payload (base64url decode middle segment)
        String[] parts = accessToken.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String jti = parse(payloadJson).path("jti").asText();

        logout(accessToken);

        // Check Redis TTL
        Long ttl = stringRedisTemplate.getExpire("blacklist:" + jti,
                java.util.concurrent.TimeUnit.SECONDS);

        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(900L); // access TTL = 15m = 900s
    }

    // ---------------------------------------------------------------------------
    // AC#11 — tamperedToken_returns401_invalidToken
    // auth.md: "Token gia mao (sai chu ky) bi tu choi (401)"
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#11 tampered token: 401 INVALID_TOKEN")
    void tamperedToken_returns401_invalidToken() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Tamper User");
        String accessToken = dataField(login(email, "Password1!").getBody(), "accessToken");

        // Corrupt the signature segment (last part of JWT)
        String[] parts = accessToken.split("\\.");
        String tamperedSignature = parts[2].substring(0, parts[2].length() - 4) + "XXXX";
        String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

        ResponseEntity<String> resp = logout(tamperedToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(resp.getBody())).isEqualTo("INVALID_TOKEN");
    }

    // ---------------------------------------------------------------------------
    // AC#14 (RBAC slice) — audience_callsOrganizerEndpoint_returns403
    // auth.md: "User role AUDIENCE khong truy cap duoc endpoint danh cho ORGANIZER (403)"
    // Uses TestRbacStubController loaded under "test" profile
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#14a RBAC: AUDIENCE calling ORGANIZER endpoint returns 403")
    void audience_callsOrganizerEndpoint_returns403() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Audience User");
        String accessToken = dataField(login(email, "Password1!").getBody(), "accessToken");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/test-rbac/organizer-only",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(resp.getBody())).isEqualTo("FORBIDDEN");
    }

    // ---------------------------------------------------------------------------
    // AC#14b — AUDIENCE can access audience endpoint (positive RBAC)
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#14b RBAC: AUDIENCE calling audience-only endpoint returns 200")
    void audience_callsAudienceEndpoint_returns200() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Audience User2");
        String accessToken = dataField(login(email, "Password1!").getBody(), "accessToken");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/test-rbac/audience-only",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------------
    // AC#14c — CHECKIN_STAFF: unauthenticated cannot access checkin endpoint
    // (We register as AUDIENCE — no CHECKIN_STAFF assignment in 1.24 scope —
    //  so AUDIENCE calling checkin-only returns 403)
    // auth.md: "User role CHECKIN_STAFF chi truy cap duoc chuc nang check-in"
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#14c RBAC: AUDIENCE calling CHECKIN_STAFF endpoint returns 403")
    void audience_callsCheckinEndpoint_returns403() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Not Checkin User");
        String accessToken = dataField(login(email, "Password1!").getBody(), "accessToken");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/test-rbac/checkin-only",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
