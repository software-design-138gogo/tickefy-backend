package com.tickefy.eticket.support;

import io.jsonwebtoken.Jwts;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

public final class JwtTestTokenFactory {

    private JwtTestTokenFactory() {
    }

    public static String bearer(String subject, String... roles) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(subject)
                .issuer("tickefy-auth-service")
                .audience().add("tickefy-api").and()
                .claim("roles", List.of(roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(privateKey())
                .compact();
        return "Bearer " + token;
    }

    private static PrivateKey privateKey() {
        try (InputStream is = JwtTestTokenFactory.class.getResourceAsStream("/keys/jwt-dev-private.pem")) {
            if (is == null) {
                throw new IllegalStateException("Missing test private key");
            }
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String stripped = pem
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build test JWT", e);
        }
    }
}
