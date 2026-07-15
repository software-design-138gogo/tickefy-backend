package com.tickefy.event.modules.concert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.tickefy.event.common.exception.ApiException;
import com.tickefy.event.common.exception.ErrorCode;
import com.tickefy.event.config.CacheProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Two-tier cache-aside for public concert reads. */
@Slf4j
@Service
public class ConcertCacheService {

    private static final String SENTINEL_NULL = "__NULL__";
    private static final String KEY_PREFIX_CONCERT = "cache:events:";
    private static final String KEY_PREFIX_LIST = "cache:events:list:";
    private static final String LOCK_PREFIX = "lock:events:";
    private static final long REDIS_RETRY_COOLDOWN_NANOS = Duration.ofSeconds(1).toNanos();

    private final Cache<String, String> concertL1Cache;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ConcertRepository concertRepository;
    private final ObjectMapper objectMapper;
    private final CacheProperties cacheProperties;

    private final ConcurrentMap<String, CompletableFuture<ConcertResponse>> localDetailLoads =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Page<ConcertResponse>>> localListLoads =
            new ConcurrentHashMap<>();
    private final AtomicLong redisUnavailableUntilNanos = new AtomicLong();

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

    /** L1 (Caffeine) -> L2 (Redis) -> DB protected by a distributed mutex. */
    public ConcertResponse getConcertById(UUID id) {
        String cacheKey = KEY_PREFIX_CONCERT + id;

        String l1Value = concertL1Cache.getIfPresent(cacheKey);
        if (l1Value != null) {
            ConcertResponse cached = readDetailValue(cacheKey, l1Value, "L1", id);
            if (SENTINEL_NULL.equals(l1Value) || cached != null) {
                return cached;
            }
        }

        RedisRead l2Read = readRedis(cacheKey);
        if (!l2Read.available()) {
            return loadDetailLocally(id, cacheKey);
        }
        if (l2Read.value() != null) {
            ConcertResponse cached = readDetailValue(cacheKey, l2Read.value(), "L2", id);
            if (SENTINEL_NULL.equals(l2Read.value()) || cached != null) {
                concertL1Cache.put(cacheKey, l2Read.value());
                return cached;
            }
        }

        return loadDetailWithDistributedLock(id, cacheKey);
    }

    /** Redis L2 cache for pageable concert lists. */
    public Page<ConcertResponse> getConcertList(ConcertStatus status, Pageable pageable) {
        String cacheKey = listCacheKey(status, pageable);
        RedisRead cacheRead = readRedis(cacheKey);

        if (!cacheRead.available()) {
            return loadListLocally(status, pageable, cacheKey);
        }
        if (cacheRead.value() != null) {
            Page<ConcertResponse> cached = deserializePage(cacheKey, cacheRead.value(), pageable);
            if (cached != null) {
                log.debug("[Cache] L2 HIT for concert list: {}", cacheKey);
                return cached;
            }
        }

        return loadListWithDistributedLock(status, pageable, cacheKey);
    }

    /** Evicts detail and optionally list caches only after the surrounding transaction commits. */
    public void evictAfterCommit(UUID concertId, boolean includeList) {
        Runnable eviction =
                () -> {
                    evict(concertId);
                    if (includeList) {
                        evictList();
                    }
                };

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eviction.run();
                        }
                    });
            return;
        }

        eviction.run();
    }

    /** Immediate eviction for callers that are already outside a transaction. */
    public void evict(UUID concertId) {
        String cacheKey = KEY_PREFIX_CONCERT + concertId;
        concertL1Cache.invalidate(cacheKey);
        deleteRedis(cacheKey);
        publishInvalidation(concertId.toString());
        log.info("[Cache] Evicted cache for concert: {}", concertId);
    }

    /** Evicts every pageable list variant after a concert write. */
    public void evictList() {
        try {
            java.util.Set<String> keys = redisTemplate.keys(KEY_PREFIX_LIST + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception exception) {
            log.warn("[Cache] Redis unavailable while evicting concert list cache", exception);
        }
        publishInvalidation("LIST_CHANGED");
        log.info("[Cache] Evicted list cache");
    }

    private ConcertResponse loadDetailWithDistributedLock(UUID id, String cacheKey) {
        RLock lock;
        boolean locked;
        try {
            lock = redissonClient.getLock(LOCK_PREFIX + id);
            locked = lock.tryLock(lockAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            log.warn(
                    "[Cache] Distributed lock unavailable for concert {}; using local single-flight",
                    id,
                    exception);
            return loadDetailLocally(id, cacheKey);
        }

        if (!locked) {
            ConcertResponse completed = readDetailAfterLockWait(id, cacheKey);
            if (completed != null || SENTINEL_NULL.equals(concertL1Cache.getIfPresent(cacheKey))) {
                return completed;
            }
            RedisRead finalRead = readRedis(cacheKey);
            if (!finalRead.available()) {
                return loadDetailLocally(id, cacheKey);
            }
            throw cacheLoadTimeout("concert " + id);
        }

        try {
            ConcertResponse completed = readDetailAfterLockWait(id, cacheKey);
            if (completed != null || SENTINEL_NULL.equals(concertL1Cache.getIfPresent(cacheKey))) {
                return completed;
            }
            return loadDetailFromDatabase(id, cacheKey);
        } finally {
            unlockSafely(lock, "concert " + id);
        }
    }

    private ConcertResponse readDetailAfterLockWait(UUID id, String cacheKey) {
        String l1Value = concertL1Cache.getIfPresent(cacheKey);
        if (l1Value != null) {
            ConcertResponse cached = readDetailValue(cacheKey, l1Value, "L1", id);
            if (SENTINEL_NULL.equals(l1Value) || cached != null) {
                return cached;
            }
        }

        RedisRead redisRead = readRedis(cacheKey);
        if (redisRead.available() && redisRead.value() != null) {
            ConcertResponse cached = readDetailValue(cacheKey, redisRead.value(), "L2", id);
            if (SENTINEL_NULL.equals(redisRead.value()) || cached != null) {
                concertL1Cache.put(cacheKey, redisRead.value());
                return cached;
            }
        }
        return null;
    }

    private ConcertResponse loadDetailLocally(UUID id, String cacheKey) {
        return singleFlight(
                cacheKey,
                localDetailLoads,
                () -> {
                    String l1Value = concertL1Cache.getIfPresent(cacheKey);
                    if (l1Value != null) {
                        ConcertResponse cached = readDetailValue(cacheKey, l1Value, "L1", id);
                        if (SENTINEL_NULL.equals(l1Value) || cached != null) {
                            return cached;
                        }
                    }
                    return loadDetailFromDatabase(id, cacheKey);
                });
    }

    private ConcertResponse loadDetailFromDatabase(UUID id, String cacheKey) {
        Optional<Concert> concert = concertRepository.findByIdWithDetails(id);
        if (concert.isEmpty()) {
            writeRedis(
                    cacheKey,
                    SENTINEL_NULL,
                    Duration.ofSeconds(cacheProperties.getNullResultTtlSeconds()));
            concertL1Cache.put(cacheKey, SENTINEL_NULL);
            log.info("[Cache] DB miss - cached null sentinel for concert: {}", id);
            return null;
        }

        ConcertResponse response = ConcertResponse.from(concert.get());
        String json = serialize(response);
        long ttlMinutes =
                TimeUnit.HOURS.toMinutes(cacheProperties.getConcertDetail().getL2TtlHours())
                        + jitter(cacheProperties.getConcertDetail().getL2JitterMinutes());
        writeRedis(cacheKey, json, Duration.ofMinutes(ttlMinutes));
        concertL1Cache.put(cacheKey, json);
        log.info("[Cache] DB hit - cached concert: {} (TTL={}m)", id, ttlMinutes);
        return response;
    }

    private Page<ConcertResponse> loadListWithDistributedLock(
            ConcertStatus status, Pageable pageable, String cacheKey) {
        RLock lock;
        boolean locked;
        try {
            String lockKey = LOCK_PREFIX + "list:" + cacheKey.substring(KEY_PREFIX_LIST.length());
            lock = redissonClient.getLock(lockKey);
            locked = lock.tryLock(lockAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            log.warn(
                    "[Cache] Distributed lock unavailable for concert list {}; using local single-flight",
                    cacheKey,
                    exception);
            return loadListLocally(status, pageable, cacheKey);
        }

        if (!locked) {
            RedisRead finalRead = readRedis(cacheKey);
            if (!finalRead.available()) {
                return loadListLocally(status, pageable, cacheKey);
            }
            if (finalRead.value() != null) {
                Page<ConcertResponse> cached =
                        deserializePage(cacheKey, finalRead.value(), pageable);
                if (cached != null) {
                    return cached;
                }
            }
            throw cacheLoadTimeout("concert list");
        }

        try {
            RedisRead secondRead = readRedis(cacheKey);
            if (secondRead.available() && secondRead.value() != null) {
                Page<ConcertResponse> cached =
                        deserializePage(cacheKey, secondRead.value(), pageable);
                if (cached != null) {
                    return cached;
                }
            }
            return loadListFromDatabase(status, pageable, cacheKey);
        } finally {
            unlockSafely(lock, "concert list");
        }
    }

    private Page<ConcertResponse> loadListLocally(
            ConcertStatus status, Pageable pageable, String cacheKey) {
        return singleFlight(
                cacheKey,
                localListLoads,
                () -> {
                    RedisRead retry = readRedis(cacheKey);
                    if (retry.available() && retry.value() != null) {
                        Page<ConcertResponse> cached =
                                deserializePage(cacheKey, retry.value(), pageable);
                        if (cached != null) {
                            return cached;
                        }
                    }
                    return loadListFromDatabase(status, pageable, cacheKey);
                });
    }

    private Page<ConcertResponse> loadListFromDatabase(
            ConcertStatus status, Pageable pageable, String cacheKey) {
        Page<Concert> concerts =
                status != null
                        ? concertRepository.findByStatus(status, pageable)
                        : concertRepository.findByStatusIn(
                                List.of(ConcertStatus.PUBLISHED, ConcertStatus.COMPLETED), pageable);
        Page<ConcertResponse> response = concerts.map(ConcertResponse::summary);

        CachedConcertPage cachedPage =
                new CachedConcertPage(response.getContent(), response.getTotalElements());
        long ttlMinutes =
                cacheProperties.getConcertList().getL2TtlMinutes()
                        + jitter(cacheProperties.getConcertList().getL2JitterMinutes());
        writeRedis(cacheKey, serialize(cachedPage), Duration.ofMinutes(ttlMinutes));
        log.info("[Cache] DB hit - cached concert list {} (TTL={}m)", cacheKey, ttlMinutes);
        return response;
    }

    private ConcertResponse readDetailValue(
            String cacheKey, String value, String tier, UUID concertId) {
        if (SENTINEL_NULL.equals(value)) {
            log.debug("[Cache] {} HIT (null sentinel) for concert: {}", tier, concertId);
            return null;
        }
        try {
            ConcertResponse response = objectMapper.readValue(value, ConcertResponse.class);
            log.debug("[Cache] {} HIT for concert: {}", tier, concertId);
            return response;
        } catch (Exception exception) {
            log.warn("[Cache] Invalid {} value for {}; evicting it", tier, cacheKey, exception);
            concertL1Cache.invalidate(cacheKey);
            deleteRedis(cacheKey);
            return null;
        }
    }

    private Page<ConcertResponse> deserializePage(
            String cacheKey, String json, Pageable pageable) {
        try {
            CachedConcertPage cached = objectMapper.readValue(json, CachedConcertPage.class);
            return new PageImpl<>(cached.content(), pageable, cached.totalElements());
        } catch (Exception exception) {
            log.warn("[Cache] Invalid concert list value for {}; evicting it", cacheKey, exception);
            deleteRedis(cacheKey);
            return null;
        }
    }

    private String listCacheKey(ConcertStatus status, Pageable pageable) {
        String statusKey = status == null ? "PUBLIC" : status.name();
        String sortKey =
                pageable.getSort().stream()
                        .map(order -> order.getProperty() + "-" + order.getDirection().name())
                        .collect(Collectors.joining(","));
        if (sortKey.isBlank()) {
            sortKey = "UNSORTED";
        }
        return KEY_PREFIX_LIST
                + statusKey
                + ":page:"
                + pageable.getPageNumber()
                + ":size:"
                + pageable.getPageSize()
                + ":sort:"
                + sortKey;
    }

    private long lockAcquireTimeoutMs() {
        CacheProperties.StampedeProps stampede = cacheProperties.getStampede();
        try {
            return Math.max(
                    stampede.getLockWaitMs(),
                    Math.multiplyExact(
                            stampede.getLockWaitMs(), (long) stampede.getMaxRetries()));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private int jitter(int upperExclusive) {
        return upperExclusive > 0 ? ThreadLocalRandom.current().nextInt(upperExclusive) : 0;
    }

    private RedisRead readRedis(String key) {
        if (redisUnavailableUntilNanos.get() > System.nanoTime()) {
            return new RedisRead(false, null);
        }
        try {
            String value = redisTemplate.opsForValue().get(key);
            redisUnavailableUntilNanos.set(0);
            return new RedisRead(true, value);
        } catch (Exception exception) {
            markRedisUnavailable("reading", key, exception);
            return new RedisRead(false, null);
        }
    }

    private void writeRedis(String key, String value, Duration ttl) {
        if (redisUnavailableUntilNanos.get() > System.nanoTime()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
            redisUnavailableUntilNanos.set(0);
        } catch (Exception exception) {
            markRedisUnavailable("writing", key, exception);
        }
    }

    private void deleteRedis(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception exception) {
            log.warn("[Cache] Redis unavailable while deleting key={}", key, exception);
        }
    }

    private void publishInvalidation(String payload) {
        try {
            redisTemplate.convertAndSend(cacheProperties.getInvalidationChannel(), payload);
        } catch (Exception exception) {
            log.warn("[Cache] Redis unavailable while publishing invalidation={}", payload, exception);
        }
    }

    private void markRedisUnavailable(String operation, String key, Exception exception) {
        long now = System.nanoTime();
        long unavailableUntil = now + REDIS_RETRY_COOLDOWN_NANOS;
        long previous =
                redisUnavailableUntilNanos.getAndUpdate(
                        current -> Math.max(current, unavailableUntil));
        if (previous <= now) {
            log.warn(
                    "[Cache] Redis unavailable while {} key={}; using local fallback: {}",
                    operation,
                    key,
                    exception.toString());
        }
    }

    private void unlockSafely(RLock lock, String resource) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception exception) {
            log.warn("[Cache] Failed to release distributed lock for {}", resource, exception);
        }
    }

    private ApiException cacheLoadTimeout(String resource) {
        return new ApiException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Timed out waiting for cache load: " + resource,
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    private <T> T singleFlight(
            String key,
            ConcurrentMap<String, CompletableFuture<T>> inFlightLoads,
            Supplier<T> loader) {
        CompletableFuture<T> newLoad = new CompletableFuture<>();
        CompletableFuture<T> activeLoad = inFlightLoads.putIfAbsent(key, newLoad);
        if (activeLoad != null) {
            return await(activeLoad);
        }

        try {
            T value = loader.get();
            newLoad.complete(value);
            return value;
        } catch (Throwable throwable) {
            newLoad.completeExceptionally(throwable);
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(throwable);
        } finally {
            inFlightLoads.remove(key, newLoad);
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("[Cache] Failed to serialize cache value", exception);
        }
    }

    private record RedisRead(boolean available, String value) {}

    private record CachedConcertPage(List<ConcertResponse> content, long totalElements) {}
}
