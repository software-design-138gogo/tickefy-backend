package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tickefy.auth.modules.auth.security.JwtBlacklistService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * AC#13 — Pure unit test for JwtBlacklistService fail-safe behavior.
 * Uses Mockito mocks — NO Docker, NO Spring context, runs offline.
 * Verifies: Redis down → isBlacklisted returns false (fail-safe, no exception).
 */
class JwtBlacklistServiceFailSafeTest {

    private StringRedisTemplate mockRedisTemplate;
    private ValueOperations<String, String> mockValueOps;
    private JwtBlacklistService blacklistService;

    @BeforeEach
    void setUp() {
        mockRedisTemplate = mock(StringRedisTemplate.class);
        mockValueOps = mock(ValueOperations.class);
        when(mockRedisTemplate.opsForValue()).thenReturn(mockValueOps);
        blacklistService = new JwtBlacklistService(mockRedisTemplate, "tickefy:auth:token:blacklist:");
    }

    // ---------------------------------------------------------------------------
    // AC#13 — blacklist_redisDown_failSafe_allowsValidToken
    // auth.md: "Redis down khi check blacklist — Fail-safe: cho phep token di qua"
    // JwtBlacklistService.isBlacklisted must return false (not throw) when Redis is down
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#13 fail-safe: Redis down in isBlacklisted returns false (no exception)")
    void blacklist_redisDown_failSafe_allowsValidToken() {
        // Simulate Redis connection failure
        DataAccessException redisDown = new DataAccessException("Connection refused") {};
        when(mockRedisTemplate.hasKey(anyString())).thenThrow(redisDown);

        String jti = "test-jti-12345";

        // Must NOT throw
        boolean result = blacklistService.isBlacklisted(jti);
        // Must return false — fail-safe lets valid token through
        assertThat(result).isFalse();
    }

    // ---------------------------------------------------------------------------
    // Additional: blacklist() when Redis down logs error but does NOT throw
    // auth.md: "neu Redis down → log ERROR (logout coi nhu best-effort)"
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#13 bonus: blacklist() when Redis down does not throw (best-effort)")
    void blacklist_redisDown_doesNotThrow() {
        DataAccessException redisDown = new DataAccessException("Connection refused") {};
        doThrow(redisDown).when(mockValueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> blacklistService.blacklist("some-jti", Duration.ofSeconds(900)))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------
    // Positive: isBlacklisted returns true when Redis has the key
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#13 bonus: isBlacklisted returns true when Redis returns true for key")
    void isBlacklisted_redisHasKey_returnsTrue() {
        String jti = "blacklisted-jti";
        when(mockRedisTemplate.hasKey("tickefy:auth:token:blacklist:" + jti)).thenReturn(Boolean.TRUE);

        assertThat(blacklistService.isBlacklisted(jti)).isTrue();
    }

    // ---------------------------------------------------------------------------
    // Positive: isBlacklisted returns false when Redis does not have the key
    // ---------------------------------------------------------------------------
    @Test
    @DisplayName("AC#13 bonus: isBlacklisted returns false when Redis key absent")
    void isBlacklisted_redisNoKey_returnsFalse() {
        String jti = "clean-jti";
        when(mockRedisTemplate.hasKey("tickefy:auth:token:blacklist:" + jti)).thenReturn(Boolean.FALSE);

        assertThat(blacklistService.isBlacklisted(jti)).isFalse();
    }
}
