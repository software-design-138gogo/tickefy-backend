package com.tickefy.event.modules.concert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.tickefy.event.config.CacheProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConcertCacheServiceTest {

    @Mock private Cache<String, String> concertL1Cache;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private RedissonClient redissonClient;
    @Mock private ConcertRepository concertRepository;
    @Mock private RLock rLock;

    private ObjectMapper objectMapper;
    private CacheProperties cacheProperties;
    private ConcertCacheService cacheService;

    private UUID concertId;
    private String l1Key;
    private ConcertResponse mockResponse;
    private String mockJson;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cacheProperties = new CacheProperties();

        cacheService = new ConcertCacheService(
                concertL1Cache,
                redisTemplate,
                redissonClient,
                concertRepository,
                objectMapper,
                cacheProperties);

        concertId = UUID.randomUUID();
        l1Key = "cache:events:" + concertId;

        Concert concert = new Concert();
        ReflectionTestUtils.setField(concert, "id", concertId);
        concert.setTitle("Test");
        concert.setStatus(ConcertStatus.DRAFT);
        
        mockResponse = ConcertResponse.from(concert);
        mockJson = objectMapper.writeValueAsString(mockResponse);
    }

    @Test
    void getConcertById_L1Hit_ShouldReturnDirectly() {
        when(concertL1Cache.getIfPresent(l1Key)).thenReturn(mockJson);

        ConcertResponse result = cacheService.getConcertById(concertId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(concertId);
        verify(redisTemplate, never()).opsForValue();
        verify(concertRepository, never()).findByIdWithDetails(any());
    }

    @Test
    void getConcertById_L2Hit_ShouldPopulateL1AndReturn() {
        when(concertL1Cache.getIfPresent(l1Key)).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(l1Key)).thenReturn(mockJson);

        ConcertResponse result = cacheService.getConcertById(concertId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(concertId);
        verify(concertL1Cache).put(l1Key, mockJson);
        verify(concertRepository, never()).findByIdWithDetails(any());
    }

    @Test
    void getConcertById_Miss_ShouldAcquireLockAndQueryDB() throws Exception {
        when(concertL1Cache.getIfPresent(l1Key)).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(l1Key)).thenReturn(null); // initial L2 check
        
        when(redissonClient.getLock("lock:events:" + concertId)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Concert concert = new Concert();
        ReflectionTestUtils.setField(concert, "id", concertId);
        when(concertRepository.findByIdWithDetails(concertId)).thenReturn(Optional.of(concert));

        ConcertResponse result = cacheService.getConcertById(concertId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(concertId);
        verify(valueOperations).set(eq(l1Key), anyString(), any(Duration.class));
        verify(concertL1Cache).put(eq(l1Key), anyString());
        verify(rLock).unlock();
    }

    @Test
    void getConcertById_DBMiss_ShouldCacheNullSentinel() throws Exception {
        when(concertL1Cache.getIfPresent(l1Key)).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(l1Key)).thenReturn(null);
        
        when(redissonClient.getLock("lock:events:" + concertId)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        when(concertRepository.findByIdWithDetails(concertId)).thenReturn(Optional.empty());

        ConcertResponse result = cacheService.getConcertById(concertId);

        assertThat(result).isNull();
        verify(valueOperations).set(eq(l1Key), eq("__NULL__"), any(Duration.class));
        verify(concertL1Cache).put(l1Key, "__NULL__");
        verify(rLock).unlock();
    }

    @Test
    void evict_ShouldDeleteFromL2L1AndBroadcast() {
        cacheService.evict(concertId);

        verify(redisTemplate).delete(l1Key);
        verify(concertL1Cache).invalidate(l1Key);
        verify(redisTemplate).convertAndSend(cacheProperties.getInvalidationChannel(), concertId.toString());
    }

    @Test
    void evictList_ShouldDeletePatternAndBroadcast() {
        java.util.Set<String> keys = java.util.Set.of("cache:events:list:1");
        when(redisTemplate.keys("cache:events:list:*")).thenReturn(keys);

        cacheService.evictList();

        verify(redisTemplate).delete(keys);
        verify(redisTemplate).convertAndSend(cacheProperties.getInvalidationChannel(), "LIST_CHANGED");
    }
}
