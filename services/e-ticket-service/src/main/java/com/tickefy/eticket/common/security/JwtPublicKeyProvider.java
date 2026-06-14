package com.tickefy.eticket.common.security;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Loads the RS256 public key for JWT verification.
 * This service is VERIFY-ONLY — it does NOT have access to the private key.
 * The private key lives exclusively in auth-service.
 */
@Component
public class JwtPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtPublicKeyProvider.class);

    private final String publicKeyPath;
    private final ResourceLoader resourceLoader;

    private PublicKey publicKey;

    public JwtPublicKeyProvider(
            @Value("${app.jwt.public-key:classpath:keys/jwt-dev-public.pem}") String publicKeyPath,
            ResourceLoader resourceLoader) {
        this.publicKeyPath = publicKeyPath;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        log.info("Loading JWT RS256 public key from: {}", publicKeyPath);
        this.publicKey = loadPublicKey(publicKeyPath);
        log.info("JWT RS256 public key loaded successfully");
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    private PublicKey loadPublicKey(String path)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        Resource resource = resourceLoader.getResource(path);
        try (InputStream is = resource.getInputStream()) {
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Strip PEM headers, handle both LF and CRLF, clean all whitespace
            String base64 = pem.lines()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("-----"))
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.joining());
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        }
    }
}
