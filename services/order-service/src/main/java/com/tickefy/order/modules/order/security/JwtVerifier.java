package com.tickefy.order.modules.order.security;

import com.tickefy.order.common.exception.ApiException;
import com.tickefy.order.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JwtVerifier {

    private final JwtKeyProvider jwtKeyProvider;
    private final JwtProperties jwtProperties;

    public JwtVerifier(JwtKeyProvider jwtKeyProvider, JwtProperties jwtProperties) {
        this.jwtKeyProvider = jwtKeyProvider;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Parse and verify JWT. Throws ApiException(401, INVALID_TOKEN) on any failure.
     */
    public Claims verify(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(jwtKeyProvider.getPublicKey())
                    .requireIssuer(jwtProperties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired token", HttpStatus.UNAUTHORIZED);
        }
    }
}
