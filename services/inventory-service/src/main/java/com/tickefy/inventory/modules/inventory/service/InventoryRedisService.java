package com.tickefy.inventory.modules.inventory.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class InventoryRedisService {

    private static final Logger log = LoggerFactory.getLogger(InventoryRedisService.class);

    private final StringRedisTemplate redisTemplate;
    private final ResourceLoader resourceLoader;

    @Value("${app.redis.stock-key-prefix:tickefy:inventory:available:}")
    private String stockKeyPrefix;

    @Value("${app.redis.user-limit-key-prefix:tickefy:inventory:user-limit:}")
    private String userLimitKeyPrefix;

    @Value("${app.redis.meta-key-prefix:tickefy:inventory:meta:}")
    private String metaKeyPrefix;

    @Value("${app.inventory.lua.reserve-script:classpath:lua/reserve.lua}")
    private String luaScriptPath;

    private DefaultRedisScript<Long> reserveScript;

    public InventoryRedisService(StringRedisTemplate redisTemplate, ResourceLoader resourceLoader) {
        this.redisTemplate = redisTemplate;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() throws IOException {
        Resource resource = resourceLoader.getResource(luaScriptPath);
        try (InputStream is = resource.getInputStream()) {
            String scriptText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            reserveScript = new DefaultRedisScript<>();
            reserveScript.setScriptText(scriptText);
            reserveScript.setResultType(Long.class);
            log.info("Lua reserve script loaded from {}", luaScriptPath);
        }
    }

    public String stockKey(UUID ticketTypeId) {
        return stockKeyPrefix + ticketTypeId;
    }

    public String userLimitKey(UUID userId, UUID ticketTypeId) {
        return userLimitKeyPrefix + userId + ":" + ticketTypeId;
    }

    public String metaKey(UUID ticketTypeId) {
        return metaKeyPrefix + ticketTypeId;
    }

    /**
     * Execute Lua reserve script atomically.
     * @return 1=ok, -1=per-user limit exceeded, -2=sold out
     */
    public long executeReserve(UUID ticketTypeId, UUID userId, int qty, int perUserLimit) {
        List<String> keys = Arrays.asList(stockKey(ticketTypeId), userLimitKey(userId, ticketTypeId));
        Long result = redisTemplate.execute(reserveScript, keys, String.valueOf(qty), String.valueOf(perUserLimit));
        return result == null ? -2L : result;
    }

    /**
     * Compensate: undo a successful Lua reserve (DB write failed).
     */
    public void compensateReserve(UUID ticketTypeId, UUID userId, int qty) {
        try {
            redisTemplate.opsForValue().increment(stockKey(ticketTypeId), qty);
            redisTemplate.opsForValue().increment(userLimitKey(userId, ticketTypeId), -qty);
        } catch (Exception e) {
            log.error("Failed to compensate Redis after DB failure for ticketTypeId={} userId={}", ticketTypeId, userId, e);
        }
    }

    /**
     * Seed stock counter (call after create).
     */
    public void seedStock(UUID ticketTypeId, int totalQty) {
        try {
            redisTemplate.opsForValue().set(stockKey(ticketTypeId), String.valueOf(totalQty));
            log.debug("Seeded stock key={} value={}", stockKey(ticketTypeId), totalQty);
        } catch (Exception e) {
            log.warn("Redis down during stock seed for ticketTypeId={}. Will be recovered on next access (M3).", ticketTypeId, e);
        }
    }

    /**
     * Seed meta hash (M2). perUserLimit=-1 means unlimited.
     */
    public void seedMeta(
            UUID ticketTypeId, Integer perUserLimit, int price, Instant saleStartAt, Instant saleEndAt) {
        try {
            String key = metaKey(ticketTypeId);
            redisTemplate.opsForHash().putAll(key, Map.of(
                    "perUserLimit", perUserLimit == null ? "-1" : String.valueOf(perUserLimit),
                    "price", String.valueOf(price),
                    "saleStartAt", String.valueOf(saleStartAt.toEpochMilli()),
                    "saleEndAt", String.valueOf(saleEndAt.toEpochMilli())));
            log.debug("Seeded meta key={}", key);
        } catch (Exception e) {
            log.warn("Redis down during meta seed for ticketTypeId={}. Will be recovered on next access (M3).", ticketTypeId, e);
        }
    }

    /**
     * Get available stock from Redis. Returns null if key missing or Redis down.
     */
    public Integer getAvailable(UUID ticketTypeId) {
        try {
            String val = redisTemplate.opsForValue().get(stockKey(ticketTypeId));
            if (val == null) return null;
            return Integer.parseInt(val);
        } catch (Exception e) {
            log.warn("Redis unavailable when reading stock for ticketTypeId={}", ticketTypeId, e);
            return null;
        }
    }

    /**
     * Get owned count by user for a ticket type from Redis. Returns 0 if missing.
     */
    public int getUserOwned(UUID userId, UUID ticketTypeId) {
        try {
            String val = redisTemplate.opsForValue().get(userLimitKey(userId, ticketTypeId));
            return val == null ? 0 : Integer.parseInt(val);
        } catch (Exception e) {
            log.warn("Redis unavailable when reading user limit for userId={} ticketTypeId={}", userId, ticketTypeId, e);
            return 0;
        }
    }

    /**
     * Read meta from Redis (M2). Returns null if missing or Redis down.
     */
    public Map<Object, Object> getMeta(UUID ticketTypeId) {
        try {
            Map<Object, Object> meta = redisTemplate.opsForHash().entries(metaKey(ticketTypeId));
            return meta.isEmpty() ? null : meta;
        } catch (Exception e) {
            log.warn("Redis unavailable when reading meta for ticketTypeId={}", ticketTypeId, e);
            return null;
        }
    }

    /**
     * Set stock from DB value (M3 seed-if-missing).
     */
    public void setStock(UUID ticketTypeId, int value) {
        try {
            redisTemplate.opsForValue().set(stockKey(ticketTypeId), String.valueOf(value));
        } catch (Exception e) {
            log.warn("Redis unavailable when setting stock for ticketTypeId={}", ticketTypeId, e);
        }
    }

    /**
     * Check if stock key exists in Redis.
     */
    public boolean stockKeyExists(UUID ticketTypeId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(stockKey(ticketTypeId)));
        } catch (Exception e) {
            log.warn("Redis unavailable when checking stock key for ticketTypeId={}", ticketTypeId, e);
            return false;
        }
    }

    /**
     * Check if meta key exists in Redis.
     */
    public boolean metaKeyExists(UUID ticketTypeId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(metaKey(ticketTypeId)));
        } catch (Exception e) {
            log.warn("Redis unavailable when checking meta key for ticketTypeId={}", ticketTypeId, e);
            return false;
        }
    }

    public boolean isRedisAvailable() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
