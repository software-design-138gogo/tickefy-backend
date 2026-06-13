package com.tickefy.auth.modules.auth.security;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;
    private final JwtKeyProvider jwtKeyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtTokenProvider(JwtProperties jwtProperties, JwtKeyProvider jwtKeyProvider) {
        this.jwtProperties = jwtProperties;
        this.jwtKeyProvider = jwtKeyProvider;
    }

    public record AccessTokenResult(String token, String jti, Instant expiresAt) {}

    public AccessTokenResult issueAccessToken(String userId, String email, List<String> roles) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plus(jwtProperties.getAccessTtl());

        String token = Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("roles", roles)
                .id(jti)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(jwtKeyProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();

        return new AccessTokenResult(token, jti, exp);
    }

    public Claims parseAccessToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(jwtKeyProvider.getPublicKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired token", HttpStatus.UNAUTHORIZED);
        }
    }

    public String generateRefreshTokenRaw() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
