package com.tickefy.checkin.modules.checkin.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_batches")
public class SyncBatch {

    @Id
    private UUID id;

    @Column(name = "sync_batch_id", nullable = false, unique = true)
    private String syncBatchId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "concert_id", nullable = false)
    private String concertId;

    @Column(name = "gate")
    private String gate;

    @Column(name = "staff_id", nullable = false)
    private String staffId;

    @Column(name = "item_count", nullable = false)
    private int itemCount = 0;

    @Column(name = "result_payload", length = 4000)
    private String resultPayload;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSyncBatchId() { return syncBatchId; }
    public void setSyncBatchId(String syncBatchId) { this.syncBatchId = syncBatchId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getConcertId() { return concertId; }
    public void setConcertId(String concertId) { this.concertId = concertId; }
    public String getGate() { return gate; }
    public void setGate(String gate) { this.gate = gate; }
    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public String getResultPayload() { return resultPayload; }
    public void setResultPayload(String resultPayload) { this.resultPayload = resultPayload; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
