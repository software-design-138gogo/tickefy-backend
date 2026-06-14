package com.tickefy.checkin.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
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

    public JwtVerifier(
            JwtPublicKeyProvider keyProvider,
            @Value("${app.jwt.issuer:tickefy-auth}") String issuer) {
        this.keyProvider = keyProvider;
        this.issuer = issuer;
    }

    /**
     * Parse and validate a JWT access token.
     *
     * @param token Bearer token string (without "Bearer " prefix)
     * @return parsed Claims
     * @throws ExpiredJwtException  if token is expired
     * @throws JwtException         if token is invalid (wrong alg, bad sig, wrong issuer)
     */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(keyProvider.getPublicKey())
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
