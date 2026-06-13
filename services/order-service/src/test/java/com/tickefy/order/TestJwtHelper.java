package com.tickefy.order;

import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Signs RS256 JWT tokens for order-service integration tests.
 * Uses the SAME private key as auth-service (same keypair as order-service jwt-dev-public.pem).
 */
public class TestJwtHelper {

    // jwt-dev-private.pem from auth-service/src/main/resources/keys/
    // Matches order-service/src/main/resources/keys/jwt-dev-public.pem
    private static final String PRIVATE_KEY_PEM =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDk7BzCdQYVz5Wf"
            + "A6kVRAs1VGXyL7FO1Sw6VJwAAH4RXuIrOzEuDwSVle7Ew1N6OQB2trLhug2aCQEY"
            + "MwBDqaeflpOqyQBnfK4QrOTzYVYdHDljS0GE5yDVccjV/7hFZGu2LdcX6mlMb0cW"
            + "NXYk/tJ83irxuoZlzxiJa3JBFymflCuzP2KwnpN1JPXZPl4z5OX3E1BsOMrOgj49"
            + "mfaUX5JIg+xcCwwnmSPi23OwvJF1Tw9jltgckN7gC3mW+JPFwj1hK5qiSV0NGRky"
            + "P2KHhCslsUs6hc88IGJ8LlZqG9Zhgaa0vaejWeh2RJb7fD3aeFdxk9n09oh/QD6J"
            + "+WDpYajRAgMBAAECggEAMENbWOAFNWn7eHf0GOismES/7YyCjEVDOtUFwrZX3d+s"
            + "PUMQfk5qQANJQLCRk+4am+yu0ApCvdvyICD4iEtnbKz5WwNfk3Hk3N0ms+0sk5yU"
            + "uMtv984mzPldR0jPl8mxL8qAU6l3I8c+LH9+9hPHWs4YLbiOhz5fRPGjN/fjLaJQ"
            + "c2tnhPVAbdYhpXHrDXPQzm7+z2helhXzqIOnBKpUdjH22ERtHpHiOWxSTKo9ydPQ"
            + "uAP6AicJ4trmzKK7kiyWsfSydF3/UunoBCfrCl+6dltK+1bT406+Qo9Y21ehn/2l"
            + "Czci0KrVqijhr0k7adLUomrF4MCnAt+ZvybR+wGrcQKBgQD0YrT48AfwVHKUKSrB"
            + "9IagIwMa5j13Ar159oNzTjlCQTg9qoL56HWEm5A+IzHAuC2tguLjJMt8slhsXrpf"
            + "qu3MY5rDsiZEaVApDkvwqVg3FMfH4wBhM8fd/ss8GCaOuNgpqTvHUBkRV5CnALEL"
            + "zPRjoip7h34RiW6ghmucXQ8QtQKBgQDvzUXvYygON6R6CpMvKrsJg/PNITSF589V"
            + "FUd4371QCYxTU/9m/kqb050uh/SQchmI8Ay5QAKsT4RLKsNqUbTfS6dTKppPe+ia"
            + "gNnfZj6wKORM6ennBGy4kOw9FZEQJ//T7MQmZsBJn1m6W1gbE8E79AihFM4DK21e"
            + "q+zCA1V1LQKBgB92GaA2nn8FEB8c0aFYjoBNIZgz7dPFaYkrAC828c4iwU/HBMeR"
            + "cpeYw1AMjdomm9LLl9PwJ7Ys648//rRUN/rpE8J/y8dg2239pi8cTfwBU9ra0XCy"
            + "Dtf4dkeNQGF9UG7El6qIGEIQSNIHF8PSJeAxv1BZ2BP/4lsOEwp1PHxFAoGBAJ9q"
            + "p4NtN8O72ewH+7Dvh0fcIMfNu00Jvhuh+dGxa/k5X8BFpxShGJhfJa85Uqx0LeWL"
            + "L+o3U4+ZjSkrVJ3pk4Selq4DNHKCvS95WV3aavJRPPSkzIp3to88SNCS9cz3ymro"
            + "i727sTlAZjYtY3UcvOlOYi4z1oDk7eByCwMvlDBBAoGARi9lhsd2Y55lIcSZAPrl"
            + "hzeMQWFGyoj73oQJi9O+mBqj2dbdBvIesrxITPXOop+UMLbieHZtPR2Cda0Fbm9v"
            + "0xBK+5MjQNC5H/tXoRdXH5sQntUr5ZFBi4tEJmEjl4h4JzAM9sg7eLrSXXic+Crk"
            + "ODOu3dluLssF1hJrx0WaTJE=";

    private static final PrivateKey PRIVATE_KEY;

    static {
        try {
            byte[] decoded = Base64.getDecoder().decode(PRIVATE_KEY_PEM);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
            PRIVATE_KEY = KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load test private key", e);
        }
    }

    public static String generateToken(String userId, List<String> roles, long ttlMs) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuer("tickefy-auth")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMs)))
                .claim("roles", roles)
                .signWith(PRIVATE_KEY)
                .compact();
    }

    public static String generateToken(String userId, List<String> roles) {
        return generateToken(userId, roles, 15 * 60 * 1000L);
    }

    public static String generateExpiredToken(String userId, List<String> roles) {
        return generateToken(userId, roles, -1000L);
    }

    public static String tamperToken(String token) {
        return token + "TAMPERED";
    }
}
