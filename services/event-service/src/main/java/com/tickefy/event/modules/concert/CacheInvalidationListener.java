package com.tickefy.event.modules.concert;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Listens on the Redis Pub/Sub invalidation channel.
 *
 * When any instance of event-service modifies a concert and calls evict(),
 * it publishes a message here. All instances (including itself) receive the
 * message and evict the corresponding key from their local L1 Caffeine cache.
 *
 * This solves the "L1 Stale Data" problem described in caching.md §Kịch bản lỗi — Phụ.
 */
@Slf4j
@Component
public class CacheInvalidationListener implements MessageListener {

    private final Cache<String, String> concertL1Cache;

    public CacheInvalidationListener(@Qualifier("concertL1Cache") Cache<String, String> concertL1Cache) {
        this.concertL1Cache = concertL1Cache;
    }

    /**
     * Called by RedisMessageListenerContainer when a message arrives on the channel.
     * Message body = the concertId whose cache entry should be evicted.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        onCacheInvalidation(new String(message.getBody()));
    }

    /**
     * Public method used by MessageListenerAdapter (reflection-based dispatch from RedisConfig).
     */
    public void onCacheInvalidation(String concertIdOrPayload) {
        String l1Key = "cache:events:" + concertIdOrPayload;
        concertL1Cache.invalidate(l1Key);
        log.info("[CacheInvalidation] Evicted L1 cache for concert: {}", concertIdOrPayload);
    }
}
