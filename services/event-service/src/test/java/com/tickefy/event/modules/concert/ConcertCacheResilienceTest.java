package com.tickefy.event.modules.concert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tickefy.event.config.CacheProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ConcertCacheResilienceTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void listCache_secondReadUsesRedisAndQueriesDatabaseOnce() throws Exception {
        ConcurrentMap<String, String> redisStore = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = redisTemplateBackedBy(redisStore);
        RedissonClient redissonClient = mock(RedissonClient.class);
        stubLock(redissonClient);
        ConcertRepository repository = mock(ConcertRepository.class);
        Concert concert = concert(UUID.randomUUID());
        PageRequest pageable = PageRequest.of(0, 10);
        when(repository.findByStatusIn(
                        List.of(ConcertStatus.PUBLISHED, ConcertStatus.COMPLETED), pageable))
                .thenReturn(new PageImpl<>(List.of(concert), pageable, 1));

        ConcertCacheService service =
                cacheService(redisTemplate, redissonClient, repository, Caffeine.newBuilder().build());

        Page<ConcertResponse> first = service.getConcertList(null, pageable);
        Page<ConcertResponse> second = service.getConcertList(null, pageable);

        assertThat(first.getContent()).hasSize(1);
        assertThat(second.getContent()).hasSize(1);
        assertThat(redisStore.keySet()).anyMatch(key -> key.startsWith("cache:events:list:PUBLIC"));
        verify(repository, times(1))
                .findByStatusIn(
                        List.of(ConcertStatus.PUBLISHED, ConcertStatus.COMPLETED), pageable);
    }

    @Test
    void concurrentDetailMiss_usesOneDatabaseQuery() throws Exception {
        ConcurrentMap<String, String> redisStore = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = redisTemplateBackedBy(redisStore);
        RedissonClient redissonClient = mock(RedissonClient.class);
        stubLock(redissonClient);
        ConcertRepository repository = mock(ConcertRepository.class);
        UUID concertId = UUID.randomUUID();
        Concert concert = concert(concertId);
        when(repository.findByIdWithDetails(concertId))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return Optional.of(concert);
                        });

        ConcertCacheService service =
                cacheService(redisTemplate, redissonClient, repository, Caffeine.newBuilder().build());

        List<ConcertResponse> responses = runConcurrently(24, () -> service.getConcertById(concertId));

        assertThat(responses).hasSize(24).allMatch(response -> concertId.equals(response.getId()));
        verify(repository, times(1)).findByIdWithDetails(concertId);
    }

    @Test
    void redisDown_concurrentDetailMissUsesLocalSingleFlight() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        RedissonClient redissonClient = mock(RedissonClient.class);
        ConcertRepository repository = mock(ConcertRepository.class);
        UUID concertId = UUID.randomUUID();
        Concert concert = concert(concertId);
        when(repository.findByIdWithDetails(concertId))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return Optional.of(concert);
                        });

        ConcertCacheService service =
                cacheService(redisTemplate, redissonClient, repository, Caffeine.newBuilder().build());

        List<ConcertResponse> responses = runConcurrently(24, () -> service.getConcertById(concertId));

        assertThat(responses).hasSize(24).allMatch(response -> concertId.equals(response.getId()));
        verify(repository, times(1)).findByIdWithDetails(concertId);
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    void redisDown_concurrentListMissUsesLocalSingleFlight() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        RedissonClient redissonClient = mock(RedissonClient.class);
        ConcertRepository repository = mock(ConcertRepository.class);
        PageRequest pageable = PageRequest.of(0, 10);
        Concert concert = concert(UUID.randomUUID());
        when(repository.findByStatusIn(
                        List.of(ConcertStatus.PUBLISHED, ConcertStatus.COMPLETED), pageable))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return new PageImpl<>(List.of(concert), pageable, 1);
                        });

        ConcertCacheService service =
                cacheService(redisTemplate, redissonClient, repository, Caffeine.newBuilder().build());

        List<Page<ConcertResponse>> responses =
                runConcurrently(24, () -> service.getConcertList(null, pageable));

        assertThat(responses).hasSize(24).allMatch(page -> page.getTotalElements() == 1);
        verify(repository, times(1))
                .findByStatusIn(
                        List.of(ConcertStatus.PUBLISHED, ConcertStatus.COMPLETED), pageable);
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    void eviction_runsOnlyAfterTransactionCommit() {
        Cache<String, String> l1Cache = Caffeine.newBuilder().build();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ConcertCacheService service =
                cacheService(
                        redisTemplate,
                        mock(RedissonClient.class),
                        mock(ConcertRepository.class),
                        l1Cache);
        UUID concertId = UUID.randomUUID();
        String cacheKey = "cache:events:" + concertId;
        l1Cache.put(cacheKey, "cached-before-commit");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.evictAfterCommit(concertId, true);

            assertThat(l1Cache.getIfPresent(cacheKey)).isEqualTo("cached-before-commit");
            verify(redisTemplate, never()).delete(cacheKey);

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCommit();

            assertThat(l1Cache.getIfPresent(cacheKey)).isNull();
            verify(redisTemplate).delete(cacheKey);
            verify(redisTemplate).convertAndSend(anyString(), eq(concertId.toString()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisTemplateBackedBy(ConcurrentMap<String, String> store) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> store.get(invocation.getArgument(0, String.class)));
        doAnswer(
                        invocation -> {
                            store.put(
                                    invocation.getArgument(0, String.class),
                                    invocation.getArgument(1, String.class));
                            return null;
                        })
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));
        doAnswer(
                        invocation -> {
                            store.remove(invocation.getArgument(0, String.class));
                            return true;
                        })
                .when(redisTemplate)
                .delete(anyString());
        return redisTemplate;
    }

    private void stubLock(RedissonClient redissonClient) throws Exception {
        RLock lock = mock(RLock.class);
        ReentrantLock localLock = new ReentrantLock();
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(
                        invocation ->
                                localLock.tryLock(
                                        invocation.getArgument(0, Long.class),
                                        invocation.getArgument(1, TimeUnit.class)));
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> localLock.isHeldByCurrentThread());
        doAnswer(
                        invocation -> {
                            localLock.unlock();
                            return null;
                        })
                .when(lock)
                .unlock();
    }

    private ConcertCacheService cacheService(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            ConcertRepository repository,
            Cache<String, String> l1Cache) {
        return new ConcertCacheService(
                l1Cache,
                redisTemplate,
                redissonClient,
                repository,
                objectMapper,
                new CacheProperties());
    }

    private Concert concert(UUID id) {
        Concert concert = new Concert();
        ReflectionTestUtils.setField(concert, "id", id);
        concert.setTitle("Concurrent Concert");
        concert.setStatus(ConcertStatus.PUBLISHED);
        return concert;
    }

    private <T> List<T> runConcurrently(int count, ThrowingSupplier<T> supplier) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return supplier.get();
                                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) {
                values.add(future.get(5, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
