package com.tickefy.event.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configures the Caffeine L1 in-process cache.
 *
 * Constraints from caching.md:
 *  - maximumSize = 10_000 records to keep JVM heap under control
 *  - expireAfterWrite = 5 minutes
 *  - SVG seat maps MUST NOT be cached here (too large — GC risk)
 *  - Ticket counts MUST NOT be cached here (cross-instance inconsistency)
 */
@Configuration
public class CaffeineCacheConfig {

    private final CacheProperties cacheProperties;

    public CaffeineCacheConfig(CacheProperties cacheProperties) {
        this.cacheProperties = cacheProperties;
    }

    /**
     * L1 cache for concert detail JSON strings.
     * Key   : cache:events:{concertId}
     * Value : JSON string of ConcertResponse (excludes SVG content)
     */
    @Bean(name = "concertL1Cache")
    public Cache<String, String> concertL1Cache() {
        return Caffeine.newBuilder()
                .maximumSize(cacheProperties.getConcertDetail().getL1MaxSize())
                .expireAfterWrite(
                        cacheProperties.getConcertDetail().getL1ExpireMinutes(),
                        TimeUnit.MINUTES)
                .recordStats() // enables hit-rate monitoring
                .build();
    }
}
