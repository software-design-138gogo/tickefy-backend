package com.tickefy.checkin.modules.checkin.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checkin_events")
public class CheckinEvent {

    @Id
    private UUID id;

    @Column(name = "ticket_id")
    private String ticketId;

    @Column(name = "qr_token_masked", nullable = false)
    private String qrTokenMasked;

    @Column(name = "concert_id", nullable = false)
    private String concertId;

    @Column(name = "staff_id", nullable = false)
    private String staffId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "gate")
    private String gate;

    @Column(name = "result", nullable = false)
    private String result;

    @Column(name = "is_offline", nullable = false)
    private boolean offline = false;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "sync_batch_id")
    private String syncBatchId;

    @Column(name = "conflict_id")
    private UUID conflictId;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
    }

    // Getters / Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public String getQrTokenMasked() { return qrTokenMasked; }
    public void setQrTokenMasked(String qrTokenMasked) { this.qrTokenMasked = qrTokenMasked; }
    public String getConcertId() { return concertId; }
    public void setConcertId(String concertId) { this.concertId = concertId; }
    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getGate() { return gate; }
    public void setGate(String gate) { this.gate = gate; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public boolean isOffline() { return offline; }
    public void setOffline(boolean offline) { this.offline = offline; }
    public Instant getScannedAt() { return scannedAt; }
    public void setScannedAt(Instant scannedAt) { this.scannedAt = scannedAt; }
    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
    public String getSyncBatchId() { return syncBatchId; }
    public void setSyncBatchId(String syncBatchId) { this.syncBatchId = syncBatchId; }
    public UUID getConflictId() { return conflictId; }
    public void setConflictId(UUID conflictId) { this.conflictId = conflictId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Instant getCreatedAt() { return createdAt; }
}
