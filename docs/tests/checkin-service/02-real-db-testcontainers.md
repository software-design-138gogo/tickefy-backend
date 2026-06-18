# checkin-service — Real DB Tests (Testcontainers)

> Last updated: 2026-06-18  
> Command: `cd services/checkin-service && mvn -Preal-db-test verify`  
> DB: PostgreSQL `17-alpine` via Testcontainers (tự spin up, không cần Docker Compose)

## Result

```text
Tests run: 8 (IT), Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Test Classes

### `CheckinRepositoryIT`

Verify Flyway migration + JPA queries chạy đúng trên real Postgres schema.

| Test | Mục đích |
|---|---|
| `migrations_applyCleanly` | Flyway apply `checkin_service` schema không error |
| `saveCheckinEvent_thenQueryByToken` | Insert + lookup `checkin_events` bằng `qr_token_hash` |
| `markScanned_thenStatusUpdated` | Update `local_status = 'SCANNED'` hoạt động |
| `syncBatch_idempotentOnReplay` | `sync_batches` unique constraint giữ idempotency |

### `SnapshotIT`

Verify snapshot generation với real DB data.

| Test | Mục đích |
|---|---|
| `generateSnapshot_whenTicketsExist_thenOnlyIssuedIncluded` | Snapshot chỉ chứa tickets status `ISSUED` |
| `generateSnapshot_doesNotLeakRawQrToken` | Response có `qrTokenMasked` + `qrTokenHash`, không có raw `qrToken` |
| `generateSnapshot_whenConcertNotFound_thenSnapshotNotFound` | Trả `SNAPSHOT_NOT_FOUND` |
| `generateSnapshot_whenExpired_thenSnapshotExpired` | Snapshot quá hạn trả `SNAPSHOT_EXPIRED` |

## DB Schema Verified

```sql
-- checkin_events
CREATE TABLE checkin_service.checkin_events (
  id UUID PRIMARY KEY,
  qr_token_hash TEXT NOT NULL,   -- hash, KHÔNG raw QR
  concert_id TEXT NOT NULL,
  result TEXT NOT NULL,
  scanned_at TIMESTAMPTZ NOT NULL,
  staff_id TEXT NOT NULL,
  gate TEXT,
  is_offline BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (qr_token_hash, concert_id)   -- first-scan-wins
);

-- sync_batches  
CREATE TABLE checkin_service.sync_batches (
  sync_batch_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  concert_id TEXT NOT NULL,
  synced_at TIMESTAMPTZ NOT NULL
);
```

## Chạy lại

```powershell
cd services/checkin-service

# Toàn bộ real-db tests
mvn -Preal-db-test verify

# Chỉ IT class cụ thể
mvn -Preal-db-test verify -Dit.test=CheckinRepositoryIT
```

> **Note:** Testcontainers tự pull `postgres:17-alpine` nếu chưa có. Cần Docker daemon đang chạy.
