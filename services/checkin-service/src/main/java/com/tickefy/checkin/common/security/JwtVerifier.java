package com.tickefy.checkin.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RS256 JWT verifier — verify-only, no signing.
 * Replaces the old HS256-based JwtService.
 * Tokens are issued by auth-service using RS256 private key.
 * This component verifies them using the corresponding public key.
 */
@Component
public class JwtVerifier {

    private final JwtPublicKeyProvider keyProvider;
    private final String issuer;
    private final String audience;
    private final int cacheMaxSize;
    private final ConcurrentMap<String, CachedClaims> verifiedTokenCache = new ConcurrentHashMap<>();

    public JwtVerifier(
            JwtPublicKeyProvider keyProvider,
            @Value("${app.jwt.issuer:tickefy-auth-service}") String issuer,
            @Value("${app.jwt.audience:tickefy-api}") String audience,
            @Value("${app.jwt.cache-max-size:2048}") int cacheMaxSize) {
        this.keyProvider = keyProvider;
        this.issuer = issuer;
        this.audience = audience;
        this.cacheMaxSize = cacheMaxSize;
    }

    /**
     * Parse and validate a JWT access token.
     *
     * @param token Bearer token string (without "Bearer " prefix)
     * @return parsed Claims
     * @throws ExpiredJwtException  if token is expired
     * @throws JwtException         if token is invalid (wrong alg, bad sig, wrong issuer/audience)
     */
    public Claims parseAndValidate(String token) {
        Instant now = Instant.now();
        CachedClaims cached = verifiedTokenCache.get(token);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.claims();
        }
        if (cached != null) {
            verifiedTokenCache.remove(token, cached);
        }

        Claims claims = Jwts.parser()
                .verifyWith(keyProvider.getPublicKey())
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        cacheUntilExpiration(token, claims, now);
        return claims;
    }

    private void cacheUntilExpiration(String token, Claims claims, Instant now) {
        if (cacheMaxSize <= 0 || claims.getExpiration() == null) {
            return;
        }
        Instant expiresAt = claims.getExpiration().toInstant();
        if (!expiresAt.isAfter(now)) {
            return;
        }
        if (verifiedTokenCache.size() >= cacheMaxSize) {
            verifiedTokenCache.clear();
        }
        verifiedTokenCache.put(token, new CachedClaims(claims, expiresAt));
    }

    private record CachedClaims(Claims claims, Instant expiresAt) {}
}
