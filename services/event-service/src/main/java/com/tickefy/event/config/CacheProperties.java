package com.tickefy.event.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds app.cache.* from application.yml into a strongly-typed POJO.
 * Injected into ConcertCacheService and cache configs.
 */
@Component
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private ConcertDetailProps concertDetail = new ConcertDetailProps();
    private ConcertListProps concertList = new ConcertListProps();
    private long nullResultTtlSeconds = 60;
    private StampedeProps stampede = new StampedeProps();
    private String invalidationChannel = "cache:invalidation:concerts";

    // --- Nested Props ---

    public static class ConcertDetailProps {
        private int l1MaxSize = 10000;
        private int l1ExpireMinutes = 5;
        private int l2TtlHours = 24;
        private int l2JitterMinutes = 120;

        public int getL1MaxSize() { return l1MaxSize; }
        public void setL1MaxSize(int l1MaxSize) { this.l1MaxSize = l1MaxSize; }
        public int getL1ExpireMinutes() { return l1ExpireMinutes; }
        public void setL1ExpireMinutes(int l1ExpireMinutes) { this.l1ExpireMinutes = l1ExpireMinutes; }
        public int getL2TtlHours() { return l2TtlHours; }
        public void setL2TtlHours(int l2TtlHours) { this.l2TtlHours = l2TtlHours; }
        public int getL2JitterMinutes() { return l2JitterMinutes; }
        public void setL2JitterMinutes(int l2JitterMinutes) { this.l2JitterMinutes = l2JitterMinutes; }
    }

    public static class ConcertListProps {
        private int l2TtlMinutes = 60;
        private int l2JitterMinutes = 10;

        public int getL2TtlMinutes() { return l2TtlMinutes; }
        public void setL2TtlMinutes(int l2TtlMinutes) { this.l2TtlMinutes = l2TtlMinutes; }
        public int getL2JitterMinutes() { return l2JitterMinutes; }
        public void setL2JitterMinutes(int l2JitterMinutes) { this.l2JitterMinutes = l2JitterMinutes; }
    }

    public static class StampedeProps {
        private long lockWaitMs = 50;
        private long lockLeaseMs = 2000;
        private int maxRetries = 40;

        public long getLockWaitMs() { return lockWaitMs; }
        public void setLockWaitMs(long lockWaitMs) { this.lockWaitMs = lockWaitMs; }
        public long getLockLeaseMs() { return lockLeaseMs; }
        public void setLockLeaseMs(long lockLeaseMs) { this.lockLeaseMs = lockLeaseMs; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    // --- Root Getters/Setters ---

    public ConcertDetailProps getConcertDetail() { return concertDetail; }
    public void setConcertDetail(ConcertDetailProps concertDetail) { this.concertDetail = concertDetail; }
    public ConcertListProps getConcertList() { return concertList; }
    public void setConcertList(ConcertListProps concertList) { this.concertList = concertList; }
    public long getNullResultTtlSeconds() { return nullResultTtlSeconds; }
    public void setNullResultTtlSeconds(long nullResultTtlSeconds) { this.nullResultTtlSeconds = nullResultTtlSeconds; }
    public StampedeProps getStampede() { return stampede; }
    public void setStampede(StampedeProps stampede) { this.stampede = stampede; }
    public String getInvalidationChannel() { return invalidationChannel; }
    public void setInvalidationChannel(String invalidationChannel) { this.invalidationChannel = invalidationChannel; }
}
