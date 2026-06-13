package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.modules.auth.security.JwtKeyProvider;
import com.tickefy.auth.modules.auth.security.JwtProperties;
import com.tickefy.auth.modules.auth.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Pure unit tests for JwtTokenProvider — NO Spring context, NO Docker.
 * RS256 key pair generated in-memory via KeyPairGenerator.
 * JwtKeyProvider and JwtProperties are mocked via Mockito.
 *
 * AC coverage:
 *   AC#12 — issue access token with correct claims (sub/email/roles/jti/issuer/exp)
 *   AC#12 — parse rejects tokens signed by a different key
 *   AC#12 — parse rejects tokens with tampered signature
 *   AC#12 — parse rejects expired tokens
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtTokenProviderUnitTest {

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @Mock
    private JwtKeyProvider mockKeyProvider;

    @Mock
    private JwtProperties mockJwtProperties;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        otherKeyPair = gen.generateKeyPair();
    }

    private JwtTokenProvider buildProvider(PrivateKey priv, PublicKey pub, String issuer, Duration ttl) {
        when(mockKeyProvider.getPrivateKey()).thenReturn(priv);
        when(mockKeyProvider.getPublicKey()).thenReturn(pub);
        when(mockJwtProperties.getIssuer()).thenReturn(issuer);
        when(mockJwtProperties.getAccessTtl()).thenReturn(ttl);
        return new JwtTokenProvider(mockJwtProperties, mockKeyProvider);
    }

    // -----------------------------------------------------------------------
    // AC#12 — issueAccessToken produces token with correct claims
    // sub=userId, email claim, roles list, jti UUID, issuer, exp ~now+15min
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 issueAccessToken: claims sub/email/roles/jti/issuer/exp all correct")
    void issueAccessToken_allClaimsCorrect() {
        String userId = UUID.randomUUID().toString();
        String email = "qa@tickefy.com";
        List<String> roles = List.of("AUDIENCE");
        String issuer = "tickefy-auth";
        Duration ttl = Duration.ofMinutes(15);

        JwtTokenProvider provider = buildProvider(
                keyPair.getPrivate(), keyPair.getPublic(), issuer, ttl);

        Instant beforeIssue = Instant.now();
        JwtTokenProvider.AccessTokenResult result =
                provider.issueAccessToken(userId, email, roles);
        Instant afterIssue = Instant.now();

        // AccessTokenResult fields
        assertThat(result.token()).isNotBlank();
        assertThat(result.jti()).isNotBlank();
        // jti must be a valid UUID
        assertThat(UUID.fromString(result.jti())).isNotNull();
        // expiresAt must be after now
        assertThat(result.expiresAt()).isAfter(beforeIssue);

        // Parse and verify claims
        Claims claims = provider.parseAccessToken(result.token());

        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.get("email", String.class)).isEqualTo(email);

        @SuppressWarnings("unchecked")
        List<String> parsedRoles = claims.get("roles", List.class);
        assertThat(parsedRoles).containsExactly("AUDIENCE");

        assertThat(claims.getId()).isEqualTo(result.jti());
        assertThat(claims.getIssuer()).isEqualTo(issuer);

        // JWT Date has second-precision (truncated). Compare at second granularity.
        // exp must fall in [beforeIssue+ttl, afterIssue+ttl+2s] (2s slack for slow CI)
        Instant expInstant = claims.getExpiration().toInstant();
        Instant lowerBound = beforeIssue.plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant upperBound = afterIssue.plus(ttl).plusSeconds(2);
        assertThat(expInstant).isAfterOrEqualTo(lowerBound);
        assertThat(expInstant).isBeforeOrEqualTo(upperBound);
    }

    // -----------------------------------------------------------------------
    // AC#12 — multiple tokens have unique jti (no jti collision)
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 issueAccessToken: successive tokens have distinct jti values")
    void issueAccessToken_uniqueJti() {
        JwtTokenProvider provider = buildProvider(
                keyPair.getPrivate(), keyPair.getPublic(), "tickefy-auth", Duration.ofMinutes(15));

        JwtTokenProvider.AccessTokenResult r1 =
                provider.issueAccessToken(UUID.randomUUID().toString(), "a@b.com", List.of("AUDIENCE"));
        JwtTokenProvider.AccessTokenResult r2 =
                provider.issueAccessToken(UUID.randomUUID().toString(), "b@b.com", List.of("ADMIN"));

        assertThat(r1.jti()).isNotEqualTo(r2.jti());
    }

    // -----------------------------------------------------------------------
    // AC#12 — token signed by a different key is rejected with INVALID_TOKEN
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 parseAccessToken: token signed by different key → ApiException INVALID_TOKEN")
    void parseAccessToken_wrongSigningKey_throwsInvalidToken() {
        // Issue with the REAL private key
        when(mockKeyProvider.getPrivateKey()).thenReturn(keyPair.getPrivate());
        when(mockJwtProperties.getIssuer()).thenReturn("tickefy-auth");
        when(mockJwtProperties.getAccessTtl()).thenReturn(Duration.ofMinutes(15));

        JwtTokenProvider issuerProvider = new JwtTokenProvider(mockJwtProperties, mockKeyProvider);
        JwtTokenProvider.AccessTokenResult result =
                issuerProvider.issueAccessToken("user-1", "u@t.com", List.of("AUDIENCE"));

        // Now verify with the OTHER key's public key — should reject
        when(mockKeyProvider.getPublicKey()).thenReturn(otherKeyPair.getPublic());
        JwtTokenProvider verifierProvider = new JwtTokenProvider(mockJwtProperties, mockKeyProvider);

        assertThatThrownBy(() -> verifierProvider.parseAccessToken(result.token()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                    assertThat(apiEx.getStatus().value()).isEqualTo(401);
                });
    }

    // -----------------------------------------------------------------------
    // AC#12 — tampered signature (last segment corrupted) → INVALID_TOKEN
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 parseAccessToken: tampered signature → ApiException INVALID_TOKEN")
    void parseAccessToken_tamperedSignature_throwsInvalidToken() {
        JwtTokenProvider provider = buildProvider(
                keyPair.getPrivate(), keyPair.getPublic(), "tickefy-auth", Duration.ofMinutes(15));

        JwtTokenProvider.AccessTokenResult result =
                provider.issueAccessToken("user-2", "v@t.com", List.of("ORGANIZER"));

        String[] parts = result.token().split("\\.");
        // Corrupt signature: replace last 6 chars with ZZZZZZ
        String corruptedSig = parts[2].substring(0, Math.max(0, parts[2].length() - 6)) + "ZZZZZZ";
        String tampered = parts[0] + "." + parts[1] + "." + corruptedSig;

        assertThatThrownBy(() -> provider.parseAccessToken(tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                });
    }

    // -----------------------------------------------------------------------
    // AC#12 — expired token (exp in the past) → INVALID_TOKEN
    // Craft an expired token manually using jjwt directly with the same key pair.
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 parseAccessToken: expired token (exp past) → ApiException INVALID_TOKEN")
    void parseAccessToken_expiredToken_throwsInvalidToken() {
        when(mockKeyProvider.getPublicKey()).thenReturn(keyPair.getPublic());
        JwtTokenProvider verifier = new JwtTokenProvider(mockJwtProperties, mockKeyProvider);

        // Craft a token with exp = 2 seconds ago using jjwt directly (no provider needed)
        String expiredToken = Jwts.builder()
                .subject("user-expired")
                .claim("email", "exp@t.com")
                .claim("roles", List.of("AUDIENCE"))
                .id(UUID.randomUUID().toString())
                .issuer("tickefy-auth")
                .issuedAt(Date.from(Instant.now().minus(Duration.ofMinutes(16))))
                .expiration(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.parseAccessToken(expiredToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                    assertThat(apiEx.getStatus().value()).isEqualTo(401);
                });
    }

    // -----------------------------------------------------------------------
    // AC#12 — garbage string → INVALID_TOKEN (not NPE or 500)
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("AC#12 parseAccessToken: random garbage string → ApiException INVALID_TOKEN (not NPE)")
    void parseAccessToken_garbage_throwsInvalidToken() {
        when(mockKeyProvider.getPublicKey()).thenReturn(keyPair.getPublic());
        JwtTokenProvider provider = new JwtTokenProvider(mockJwtProperties, mockKeyProvider);

        assertThatThrownBy(() -> provider.parseAccessToken("not.a.jwt.at.all"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                });
    }

    // -----------------------------------------------------------------------
    // sha256Hex — deterministic, 64-char hex, NOT identity
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("sha256Hex: deterministic 64-char hex, different from plain input")
    void sha256Hex_deterministic_and_correct_length() {
        // sha256Hex does not call mockKeyProvider or mockJwtProperties, so no stubbing needed
        JwtTokenProvider provider = new JwtTokenProvider(mockJwtProperties, mockKeyProvider);
        String input = "opaque-refresh-token-abc";

        String h1 = provider.sha256Hex(input);
        String h2 = provider.sha256Hex(input);

        assertThat(h1).hasSize(64).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(input);
    }

    // -----------------------------------------------------------------------
    // generateRefreshTokenRaw — produces distinct, non-blank tokens
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("generateRefreshTokenRaw: produces distinct non-blank base64url tokens")
    void generateRefreshTokenRaw_distinct() {
        JwtTokenProvider provider = new JwtTokenProvider(mockJwtProperties, mockKeyProvider);

        String raw1 = provider.generateRefreshTokenRaw();
        String raw2 = provider.generateRefreshTokenRaw();

        assertThat(raw1).isNotBlank();
        assertThat(raw2).isNotBlank();
        assertThat(raw1).isNotEqualTo(raw2);
    }
}
