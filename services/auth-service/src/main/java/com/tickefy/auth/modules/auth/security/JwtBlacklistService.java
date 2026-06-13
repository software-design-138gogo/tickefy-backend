package com.tickefy.auth.modules.auth.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class JwtBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(JwtBlacklistService.class);

    private final StringRedisTemplate redisTemplate;
    private final String blacklistPrefix;

    public JwtBlacklistService(
            StringRedisTemplate redisTemplate,
            @Value("${app.redis.blacklist-prefix:tickefy:auth:token:blacklist:}") String blacklistPrefix) {
        this.redisTemplate = redisTemplate;
        this.blacklistPrefix = blacklistPrefix;
    }

    public void blacklist(String jti, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(blacklistPrefix + jti, "1", ttl);
        } catch (DataAccessException e) {
            log.error("Redis down — cannot blacklist jti={}, best-effort logout", jti, e);
        }
    }

    public boolean isBlacklisted(String jti) {
        try {
            Boolean exists = redisTemplate.hasKey(blacklistPrefix + jti);
            return Boolean.TRUE.equals(exists);
        } catch (DataAccessException e) {
            log.warn("Redis down — fail-safe: treating jti={} as not blacklisted", jti, e);
            return false;
        }
    }
}
