package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.modules.auth.entity.RefreshTokenEntity;
import com.tickefy.auth.modules.auth.repository.RefreshTokenRepository;
import com.tickefy.auth.modules.auth.security.JwtProperties;
import com.tickefy.auth.modules.auth.security.JwtTokenProvider;
import com.tickefy.auth.modules.auth.service.RefreshTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AC#15 — Pure unit tests for RefreshTokenService using Mockito.
 * No Docker, no Spring context needed.
 * Tests revokeAllForUser (for change-password future) and verifyRefreshToken edge cases.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceUnitTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private JwtProperties jwtProperties;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, jwtTokenProvider, jwtProperties);
    }

    // ---------------------------------------------------------------------------
    // AC#15 — revokeAllForUser makes old tokens fail verify
    // auth.md: "Doi mat khau khien refresh token cu khong dung duoc nua"
    // Test: revokeAllByUserId is called; then verifyRefreshToken on a revoked entity throws TOKEN_REVOKED
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#15 revokeAllForUser: delegates to repository revokeAllByUserId")
    void revokeAllForUser_callsRepository() {
        UUID userId = UUID.randomUUID();

        refreshTokenService.revokeAllForUser(userId);

        verify(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));
    }

    @Test
    @DisplayName("AC#15 verifyRefreshToken on revoked entity: throws TOKEN_REVOKED")
    void verifyRefreshToken_revokedEntity_throwsTokenRevoked() {
        String rawToken = "some-raw-refresh-token";
        String hash = "fakehash";
        when(jwtTokenProvider.sha256Hex(rawToken)).thenReturn(hash);

        RefreshTokenEntity revoked = RefreshTokenEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .tokenHash(hash)
                .issuedAt(Instant.now().minus(Duration.ofHours(1)))
                .expiresAt(Instant.now().plus(Duration.ofDays(6)))
                .revokedAt(Instant.now().minus(Duration.ofMinutes(5)))  // already revoked
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> refreshTokenService.verifyRefreshToken(rawToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.TOKEN_REVOKED);
                });
    }

    @Test
    @DisplayName("AC#15 verifyRefreshToken on expired entity: throws INVALID_TOKEN")
    void verifyRefreshToken_expiredEntity_throwsInvalidToken() {
        String rawToken = "expired-raw-token";
        String hash = "expiredhash";
        when(jwtTokenProvider.sha256Hex(rawToken)).thenReturn(hash);

        RefreshTokenEntity expired = RefreshTokenEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .tokenHash(hash)
                .issuedAt(Instant.now().minus(Duration.ofDays(8)))
                .expiresAt(Instant.now().minus(Duration.ofDays(1)))  // expired yesterday
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> refreshTokenService.verifyRefreshToken(rawToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                });
    }

    @Test
    @DisplayName("AC#15 verifyRefreshToken unknown token: throws INVALID_TOKEN")
    void verifyRefreshToken_unknownToken_throwsInvalidToken() {
        String rawToken = "unknown-token";
        String hash = "unknownhash";
        when(jwtTokenProvider.sha256Hex(rawToken)).thenReturn(hash);
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.verifyRefreshToken(rawToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
                });
    }

    @Test
    @DisplayName("AC#15 verifyRefreshToken valid entity: returns entity without throwing")
    void verifyRefreshToken_validEntity_returnsEntity() {
        String rawToken = "valid-raw-token";
        String hash = "validhash";
        when(jwtTokenProvider.sha256Hex(rawToken)).thenReturn(hash);

        RefreshTokenEntity valid = RefreshTokenEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .tokenHash(hash)
                .issuedAt(Instant.now().minus(Duration.ofHours(1)))
                .expiresAt(Instant.now().plus(Duration.ofDays(6)))
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(valid));

        RefreshTokenEntity result = refreshTokenService.verifyRefreshToken(rawToken);

        assertThat(result).isNotNull();
        assertThat(result.getTokenHash()).isEqualTo(hash);
    }
}
