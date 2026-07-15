package com.tickefy.event.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import com.tickefy.event.modules.concert.CacheInvalidationListener;

/**
 * Redis infrastructure configuration.
 *
 * Provides:
 *  1. StringRedisTemplate — for all cache read/write/delete operations
 *  2. RedisMessageListenerContainer — subscribes to the invalidation Pub/Sub channel
 *     so this instance can evict its local L1 Caffeine cache when another instance
 *     modifies a concert (cross-instance L1 consistency).
 */
@Configuration
public class RedisConfig {

    private final CacheProperties cacheProperties;

    public RedisConfig(CacheProperties cacheProperties) {
        this.cacheProperties = cacheProperties;
    }

    /** Standard String-to-String template, reuses Spring Boot auto-configured factory. */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Container that manages the Pub/Sub subscription lifecycle.
     * Automatically reconnects on Redis restart.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationListener invalidationListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Subscribe to the invalidation channel defined in application.yml
        MessageListenerAdapter adapter = new MessageListenerAdapter(invalidationListener, "onCacheInvalidation");
        container.addMessageListener(
                adapter,
                new PatternTopic(cacheProperties.getInvalidationChannel()));

        return container;
    }
}
