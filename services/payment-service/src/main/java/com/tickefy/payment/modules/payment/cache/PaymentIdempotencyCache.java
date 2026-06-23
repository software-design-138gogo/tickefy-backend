package com.tickefy.payment.modules.payment.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentIdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(PaymentIdempotencyCache.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${app.payment.idempotency.key-prefix:tickefy:payment:idempotency:}")
    private String keyPrefix;

    @Value("${app.payment.idempotency.cache-ttl:PT24H}")
    private Duration cacheTtl;

    public PaymentIdempotencyCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String buildKey(String idempotencyKey) {
        return keyPrefix + idempotencyKey;
    }

    /**
     * Get cached paymentId for the given idempotency key.
     * Returns Optional.empty() if not found or Redis is down (fail-safe).
     */
    public Optional<UUID> get(String idempotencyKey) {
        try {
            String value = redisTemplate.opsForValue().get(buildKey(idempotencyKey));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(value));
        } catch (Exception e) {
            log.warn("Redis unavailable when reading idempotency cache for key={}", idempotencyKey, e);
            return Optional.empty();
        }
    }

    /**
     * Store paymentId for the given idempotency key with configured TTL.
     * Fail-safe: Redis down → log + no-op (KHÔNG ném exception).
     */
    public void put(String idempotencyKey, UUID paymentId) {
        try {
            redisTemplate.opsForValue().set(buildKey(idempotencyKey), paymentId.toString(), cacheTtl);
        } catch (Exception e) {
            log.warn("Redis unavailable when writing idempotency cache for key={} paymentId={}", idempotencyKey, paymentId, e);
        }
    }
}
