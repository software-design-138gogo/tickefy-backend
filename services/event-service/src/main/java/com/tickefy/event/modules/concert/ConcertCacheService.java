package com.tickefy.event.modules.concert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.tickefy.event.config.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Two-Tier Cache Service for concert data.
 *
 * Implements the Cache-aside pattern described in caching.md with protection against:
 *  - Stampede  : Redisson Mutex Lock + Spin-lock (50ms sleep, max 40 retries)
 *  - Penetration: SENTINEL_NULL stored in L2 with short TTL (60s)
 *  - Avalanche : TTL jitter applied on all L2 writes
 *  - L1 Stale  : Redis Pub/Sub broadcast triggers L1 eviction on all instances
 *
 * NOTE: This service injects ConcertRepository directly (NOT ConcertService)
 * to avoid circular dependency. ConcertService calls this class for eviction.
 */
@Slf4j
@Service
public class ConcertCacheService {

    /** Sentinel value stored in Redis to represent "entity does not exist" (Penetration guard). */
    private static final String SENTINEL_NULL = "__NULL__";

    private static final String KEY_PREFIX_CONCERT = "cache:events:";
    private static final String LOCK_PREFIX = "lock:events:";

    private final Cache<String, String> concertL1Cache;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ConcertRepository concertRepository;
    private final ObjectMapper objectMapper;
    private final CacheProperties cacheProperties;
    private final Random random = new Random();

    public ConcertCacheService(
            @Qualifier("concertL1Cache") Cache<String, String> concertL1Cache,
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            ConcertRepository concertRepository,
            ObjectMapper objectMapper,
            CacheProperties cacheProperties) {
        this.concertL1Cache = concertL1Cache;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.concertRepository = concertRepository;
        this.objectMapper = objectMapper;
        this.cacheProperties = cacheProperties;
    }

    // =========================================================================
    // READ — Two-Tier Cache-aside
    // =========================================================================

    /**
     * Get a concert by ID using Two-Tier Cache-aside:
     *   L1 (Caffeine) → L2 (Redis) → DB (with Mutex Lock)
     *
     * Returns null if the concert genuinely does not exist.
     */
    public ConcertResponse getConcertById(UUID id) {
        String l1Key = KEY_PREFIX_CONCERT + id;

        // --- Step 1: Check L1 (Caffeine) ---
        String cachedJson = concertL1Cache.getIfPresent(l1Key);
        if (cachedJson != null) {
            if (SENTINEL_NULL.equals(cachedJson)) {
                log.debug("[Cache] L1 HIT (null sentinel) for concert: {}", id);
                return null;
            }
            log.debug("[Cache] L1 HIT for concert: {}", id);
            return deserialize(cachedJson);
        }

        // --- Step 2: Check L2 (Redis) ---
        String l2Value = redisTemplate.opsForValue().get(l1Key);
        if (l2Value != null) {
            if (SENTINEL_NULL.equals(l2Value)) {
                log.debug("[Cache] L2 HIT (null sentinel) for concert: {}", id);
                // Populate L1 with sentinel to avoid repeated Redis calls
                concertL1Cache.put(l1Key, SENTINEL_NULL);
                return null;
            }
            log.debug("[Cache] L2 HIT for concert: {}", id);
            concertL1Cache.put(l1Key, l2Value);
            return deserialize(l2Value);
        }

        // --- Step 3: Cache MISS → Acquire Mutex Lock (Stampede protection) ---
        return acquireAndLoad(id, l1Key);
    }

    // =========================================================================
    // EVICT — Cache Invalidation
    // =========================================================================

    /**
     * Evicts a concert from all cache tiers and notifies other instances via Pub/Sub.
     * Called after any write operation (update, publish, cancel, AI bio update).
     *
     * Constraint from caching.md: use DEL (not Update) for transactional data.
     */
    public void evict(UUID concertId) {
        String l1Key = KEY_PREFIX_CONCERT + concertId;

        // 1. Evict from L2 (Redis)
        redisTemplate.delete(l1Key);

        // 2. Evict from local L1 (Caffeine)
        concertL1Cache.invalidate(l1Key);

        // 3. Broadcast Pub/Sub → all other instances evict their L1
        redisTemplate.convertAndSend(
                cacheProperties.getInvalidationChannel(),
                concertId.toString());

        log.info("[Cache] Evicted cache for concert: {}", concertId);
    }

    /**
     * Evict list cache when concerts are added, published, or status changes.
     */
    public void evictList() {
        try {
            // Find all list keys
            java.util.Set<String> keys = redisTemplate.keys("cache:events:list:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            // Broadcast so other instances can clear local lists if they have them
            redisTemplate.convertAndSend(
                    cacheProperties.getInvalidationChannel(),
                    "LIST_CHANGED");
            log.info("[Cache] Evicted list cache");
        } catch (Exception e) {
            log.error("[Cache] Failed to evict list cache", e);
        }
    }

    // =========================================================================
    // PRIVATE — Mutex Lock + DB load
    // =========================================================================

    /**
     * Acquires a Redisson distributed lock for the concert ID, then loads from DB.
     * If the lock is not immediately available, uses Spin-lock (sleep 50ms, retry up to 40 times).
     * Lock auto-releases after 2s to prevent deadlocks on crash.
     */
    private ConcertResponse acquireAndLoad(UUID id, String l1Key) {
        String lockKey = LOCK_PREFIX + id;
        RLock lock = redissonClient.getLock(lockKey);

        CacheProperties.StampedeProps sp = cacheProperties.getStampede();
        int maxRetries = sp.getMaxRetries();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            boolean locked = false;
            try {
                // Try-lock: non-blocking attempt
                locked = lock.tryLock(0, sp.getLockLeaseMs(), TimeUnit.MILLISECONDS);

                if (locked) {
                    // Double-check L2 (another thread may have already loaded)
                    String doubleCheck = redisTemplate.opsForValue().get(l1Key);
                    if (doubleCheck != null) {
                        if (SENTINEL_NULL.equals(doubleCheck)) {
                            concertL1Cache.put(l1Key, SENTINEL_NULL);
                            return null;
                        }
                        concertL1Cache.put(l1Key, doubleCheck);
                        return deserialize(doubleCheck);
                    }

                    // Load from DB
                    Optional<Concert> concertOpt = concertRepository.findByIdWithDetails(id);

                    if (concertOpt.isEmpty()) {
                        // Cache null sentinel (Penetration protection)
                        redisTemplate.opsForValue().set(l1Key, SENTINEL_NULL,
                                Duration.ofSeconds(cacheProperties.getNullResultTtlSeconds()));
                        concertL1Cache.put(l1Key, SENTINEL_NULL);
                        log.info("[Cache] DB miss — cached null sentinel for concert: {}", id);
                        return null;
                    }

                    // Serialize and populate caches
                    ConcertResponse response = ConcertResponse.from(concertOpt.get());
                    String json = serialize(response);

                    // TTL = base + random jitter (Avalanche protection)
                    long ttlMinutes = TimeUnit.HOURS.toMinutes(cacheProperties.getConcertDetail().getL2TtlHours())
                            + random.nextInt(cacheProperties.getConcertDetail().getL2JitterMinutes());
                    redisTemplate.opsForValue().set(l1Key, json, Duration.ofMinutes(ttlMinutes));
                    concertL1Cache.put(l1Key, json);

                    log.info("[Cache] DB hit — cached concert: {} (TTL={}m)", id, ttlMinutes);
                    return response;

                } else {
                    // Spin-lock: sleep and retry
                    if (attempt < maxRetries) {
                        log.debug("[Cache] Lock busy for concert: {}, spinning (attempt {}/{})", id, attempt + 1, maxRetries);
                        Thread.sleep(sp.getLockWaitMs());

                        // Re-check L2 after sleeping (another thread may have loaded)
                        String spinCheck = redisTemplate.opsForValue().get(l1Key);
                        if (spinCheck != null) {
                            if (SENTINEL_NULL.equals(spinCheck)) {
                                concertL1Cache.put(l1Key, SENTINEL_NULL);
                                return null;
                            }
                            concertL1Cache.put(l1Key, spinCheck);
                            return deserialize(spinCheck);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Cache] Interrupted while waiting for lock on concert: {}", id);
                break;
            } catch (Exception e) {
                log.error("[Cache] Error loading concert from DB: {}", id, e);
                break;
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // Fallback: lock timeout exhausted — query DB directly (degrade gracefully)
        log.warn("[Cache] Lock timeout exhausted for concert: {} — falling back to direct DB query", id);
        return concertRepository.findByIdWithDetails(id)
                .map(ConcertResponse::from)
                .orElse(null);
    }

    // =========================================================================
    // PRIVATE — Serialization helpers
    // =========================================================================

    private String serialize(ConcertResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException("[Cache] Failed to serialize ConcertResponse", e);
        }
    }

    private ConcertResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, ConcertResponse.class);
        } catch (Exception e) {
            log.error("[Cache] Failed to deserialize ConcertResponse, treating as miss: {}", e.getMessage());
            return null;
        }
    }
}
