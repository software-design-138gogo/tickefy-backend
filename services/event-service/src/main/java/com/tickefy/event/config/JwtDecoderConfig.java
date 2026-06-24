package com.tickefy.event.config;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(
            ResourceLoader resourceLoader,
            @Value("${app.security.jwt.public-key-path}") String publicKeyPath,
            @Value("${app.security.jwt.issuer}") String issuer,
            @Value("${app.security.jwt.audience}") String audience)
            throws IOException {
        Resource resource = resolveResource(resourceLoader, publicKeyPath);
        RSAPublicKey publicKey = readPublicKey(resource);

        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(publicKey)
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        audienceValidator(audience)));
        return decoder;
    }

    private Resource resolveResource(ResourceLoader resourceLoader, String path) {
        if (path.startsWith("classpath:") || path.startsWith("file:")) {
            return resourceLoader.getResource(path);
        }
        return new FileSystemResource(path);
    }

    private RSAPublicKey readPublicKey(Resource resource) throws IOException {
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "JWT public key does not exist: " + resource.getDescription());
        }

        try (InputStream inputStream = resource.getInputStream()) {
            Converter<InputStream, RSAPublicKey> converter = RsaKeyConverters.x509();
            RSAPublicKey key = converter.convert(inputStream);
            if (key == null) {
                throw new IllegalStateException(
                        "Unable to parse JWT RSA public key: " + resource.getDescription());
            }
            return key;
        }
    }

    private OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return jwt -> {
            if (jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_TOKEN,
                            "JWT does not contain the required audience.",
                            null));
        };
    }
}
