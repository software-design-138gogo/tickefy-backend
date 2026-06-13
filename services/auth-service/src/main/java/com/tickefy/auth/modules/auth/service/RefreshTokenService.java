package com.tickefy.auth.modules.auth.service;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.modules.auth.entity.RefreshTokenEntity;
import com.tickefy.auth.modules.auth.repository.RefreshTokenRepository;
import com.tickefy.auth.modules.auth.security.JwtProperties;
import com.tickefy.auth.modules.auth.security.JwtTokenProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public String createRefreshToken(UUID userId) {
        String raw = jwtTokenProvider.generateRefreshTokenRaw();
        String hash = jwtTokenProvider.sha256Hex(raw);
        Instant now = Instant.now();

        RefreshTokenEntity entity = RefreshTokenEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tokenHash(hash)
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.getRefreshTtl()))
                .build();

        refreshTokenRepository.save(entity);
        return raw;
    }

    @Transactional(readOnly = true)
    public RefreshTokenEntity verifyRefreshToken(String raw) {
        String hash = jwtTokenProvider.sha256Hex(raw);
        RefreshTokenEntity token = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_TOKEN, "Refresh token not found", HttpStatus.UNAUTHORIZED));

        if (token.getRevokedAt() != null) {
            throw new ApiException(ErrorCode.TOKEN_REVOKED, "Refresh token has been revoked", HttpStatus.UNAUTHORIZED);
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Refresh token has expired", HttpStatus.UNAUTHORIZED);
        }

        return token;
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
    }
}
