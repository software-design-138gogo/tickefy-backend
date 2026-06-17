package com.tickefy.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

  public static final String DEFAULT_POLICY_ID = "default";
  public static final String AUTH_POLICY_ID = "auth";
  public static final String PURCHASE_POLICY_ID = "purchase";

  @Bean
  RedisRateLimiter redisRateLimiter(
      RateLimitProperties properties) {
    RateLimitProperties.Policy defaultPolicy = properties.getDefaultPolicy();

    RedisRateLimiter rateLimiter = new RedisRateLimiter(
        defaultPolicy.getReplenishRate(),
        defaultPolicy.getBurstCapacity(),
        defaultPolicy.getRequestedTokens());

    rateLimiter.getConfig().put(
        AUTH_POLICY_ID,
        toRedisConfig(properties.getAuthPolicy()));

    rateLimiter.getConfig().put(
        PURCHASE_POLICY_ID,
        toRedisConfig(properties.getPurchasePolicy()));

    rateLimiter.setIncludeHeaders(true);

    return rateLimiter;
  }

  private RedisRateLimiter.Config toRedisConfig(
      RateLimitProperties.Policy policy) {
    return new RedisRateLimiter.Config()
        .setReplenishRate(
            policy.getReplenishRate())
        .setBurstCapacity(
            policy.getBurstCapacity())
        .setRequestedTokens(
            policy.getRequestedTokens());
  }
}