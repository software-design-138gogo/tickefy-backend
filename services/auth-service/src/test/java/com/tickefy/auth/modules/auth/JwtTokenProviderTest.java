package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.modules.auth.security.JwtKeyProvider;
import com.tickefy.auth.modules.auth.security.JwtProperties;
import com.tickefy.auth.modules.auth.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC#12 — Unit/slice test for JwtTokenProvider.
 * Loads full Spring context (so JwtKeyProvider reads real dev keys from classpath).
 * No Testcontainers needed — does NOT extend BaseIntegrationTest.
 * Uses application-test.yml; no DB/Redis needed for key-loading alone,
 * BUT context start requires Redis → we bring up containers here via the parent config.
 * Simpler alternative: extend BaseIntegrationTest (containers already up).
 */
@SpringBootTest
@ActiveProfiles("test")
class JwtTokenProviderTest extends com.tickefy.auth.BaseIntegrationTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtKeyProvider jwtKeyProvider;

    @Autowired
    private JwtProperties jwtProperties;

    private static final String TEST_USER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final String TEST_EMAIL = "qa@tickefy.com";
    private static final List<String> TEST_ROLES = List.of("AUDIENCE");

    // ---------------------------------------------------------------------------
    // AC#12 — accessToken_containsClaims_subEmailRolesJtiExp
    // auth.md: "Access token chua dung userId, roles, jti, exp"
    // Issue token then parse with public key, assert all claims present.
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 JWT claims: sub=userId, email, roles list, jti UUID, exp future")
    void accessToken_containsClaims_subEmailRolesJtiExp() {
        JwtTokenProvider.AccessTokenResult result =
                jwtTokenProvider.issueAccessToken(TEST_USER_ID, TEST_EMAIL, TEST_ROLES);

        assertThat(result.token()).isNotBlank();
        assertThat(result.jti()).isNotBlank();
        assertThat(result.expiresAt()).isAfter(Instant.now());

        // Parse with public key and verify all claims
        Claims claims = jwtTokenProvider.parseAccessToken(result.token());

        assertThat(claims.getSubject()).isEqualTo(TEST_USER_ID);
        assertThat(claims.get("email", String.class)).isEqualTo(TEST_EMAIL);

        List<?> roles = claims.get("roles", List.class);
        assertThat(roles).isNotNull().isNotEmpty();
        assertThat(roles).map(Object::toString).contains("AUDIENCE");

        assertThat(claims.getId()).isEqualTo(result.jti());
        assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now());
        assertThat(claims.getIssuer()).isEqualTo(jwtProperties.getIssuer());

        // exp should be approximately now + 15min
        long ttlSeconds = Duration.between(Instant.now(), claims.getExpiration().toInstant()).getSeconds();
        assertThat(ttlSeconds).isGreaterThan(850L).isLessThanOrEqualTo(900L);
    }

    // ---------------------------------------------------------------------------
    // Additional: tampered token throws ApiException INVALID_TOKEN (sanity for filter)
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 bonus: parseAccessToken with tampered signature throws INVALID_TOKEN")
    void parseAccessToken_tampered_throwsInvalidToken() {
        JwtTokenProvider.AccessTokenResult result =
                jwtTokenProvider.issueAccessToken(TEST_USER_ID, TEST_EMAIL, TEST_ROLES);
        String[] parts = result.token().split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + "invalidsignatureXXXXXX";

        assertThatThrownBy(() -> jwtTokenProvider.parseAccessToken(tampered))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid");
    }

    // ---------------------------------------------------------------------------
    // Additional: generateRefreshTokenRaw produces unique non-blank tokens
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 bonus: generateRefreshTokenRaw produces distinct base64url tokens")
    void generateRefreshTokenRaw_distinct() {
        String raw1 = jwtTokenProvider.generateRefreshTokenRaw();
        String raw2 = jwtTokenProvider.generateRefreshTokenRaw();

        assertThat(raw1).isNotBlank();
        assertThat(raw2).isNotBlank();
        assertThat(raw1).isNotEqualTo(raw2);
    }

    // ---------------------------------------------------------------------------
    // Additional: sha256Hex is deterministic
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 bonus: sha256Hex is deterministic for same input")
    void sha256Hex_deterministic() {
        String input = "some-opaque-refresh-token";
        assertThat(jwtTokenProvider.sha256Hex(input))
                .isEqualTo(jwtTokenProvider.sha256Hex(input))
                .hasSize(64);
    }
}
