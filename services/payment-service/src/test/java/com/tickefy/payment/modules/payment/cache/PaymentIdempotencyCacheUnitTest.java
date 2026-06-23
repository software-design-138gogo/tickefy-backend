package com.tickefy.payment.modules.payment.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AC8 — Redis down fail-safe in PaymentIdempotencyCache:
 *  - get() throwing RuntimeException → returns Optional.empty() (DOES NOT propagate exception)
 *  - put() throwing RuntimeException → silent no-op (DOES NOT propagate exception)
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PaymentIdempotencyCacheUnitTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private PaymentIdempotencyCache cache;

    @BeforeEach
    void setUp() {
        cache = new PaymentIdempotencyCache(redisTemplate);
        ReflectionTestUtils.setField(cache, "keyPrefix", "tickefy:payment:idempotency:");
        ReflectionTestUtils.setField(cache, "cacheTtl", Duration.ofHours(24));
    }

    // ============================================================
    // AC8-cache-get: Redis down → get() returns Optional.empty(), DOES NOT throw
    // ============================================================

    /** AC8-cache-get-1: redisTemplate.opsForValue().get() throws RuntimeException → get() returns Optional.empty(). */
    @Test
    void ac8_cacheGet_redisDown_returnsEmpty_doesNotThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        Optional<UUID> result = cache.get("any-key");

        assertThat(result)
                .as("AC8: cache.get() must return Optional.empty() when Redis is down")
                .isEmpty();
    }

    /** AC8-cache-get-2: opsForValue() itself throws → get() returns Optional.empty(), DOES NOT throw. */
    @Test
    void ac8_cacheGet_opsForValueThrows_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));

        Optional<UUID> result = cache.get("key-ops-fail");

        assertThat(result).isEmpty();
    }

    /** AC8-cache-get-3: Redis returns null (key missing) → get() returns Optional.empty(). */
    @Test
    void ac8_cacheGet_keyMissing_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        Optional<UUID> result = cache.get("missing-key");

        assertThat(result).isEmpty();
    }

    /** AC8-cache-get-4: Redis returns valid UUID string → get() returns Optional<UUID>. */
    @Test
    void ac8_cacheGet_keyPresent_returnsUuid() {
        UUID paymentId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("tickefy:payment:idempotency:known-key")).thenReturn(paymentId.toString());

        Optional<UUID> result = cache.get("known-key");

        assertThat(result).isPresent().contains(paymentId);
    }

    // ============================================================
    // AC8-cache-put: Redis down → put() is silent no-op, DOES NOT throw
    // ============================================================

    /** AC8-cache-put-1: redisTemplate.opsForValue().set() throws RuntimeException → put() does NOT throw. */
    @Test
    void ac8_cachePut_redisDown_doesNotThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("Redis write failed"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertDoesNotThrow(
                () -> cache.put("any-key", UUID.randomUUID()),
                "AC8: cache.put() must be silent no-op when Redis is down");
    }

    /** AC8-cache-put-2: opsForValue() itself throws during put() → DOES NOT throw. */
    @Test
    void ac8_cachePut_opsForValueThrows_doesNotThrow() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(
                () -> cache.put("key-ops-fail", UUID.randomUUID()),
                "AC8: cache.put() must swallow exception when Redis is down");
    }
}
