# checkin-service — Unit & Integration Tests

> Last updated: 2026-06-18  
> Command: `cd services/checkin-service && mvn test`

## Result

```text
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Test Classes

### `CheckinServiceTest` (12 tests)

| Test | Scenario | Expected |
|---|---|---|
| `scan_whenValidTicketOnline_thenAccepted` | Valid QR, đúng concert | `ACCEPTED` |
| `scan_whenAlreadyScanned_thenDuplicateRejected` | Ticket đã SCANNED | `DUPLICATE_REJECTED` |
| `scan_whenWrongConcert_thenWrongEvent` | QR thuộc concert khác | `WRONG_EVENT` |
| `scan_whenTicketCancelled_thenCancelledRejected` | Ticket status `CANCELLED` | `CANCELLED_REJECTED` |
| `scan_whenTicketRefunded_thenRefundedRejected` | Ticket status `REFUNDED` | `REFUNDED_REJECTED` |
| `scan_whenEticketServiceDown_thenUnavailable` | e-ticket HTTP 503 | `ETICKET_SERVICE_UNAVAILABLE` |
| `sync_whenValidOfflineBatch_thenSyncAccepted` | Valid offline batch | `SYNC_ACCEPTED` per item |
| `sync_whenDuplicateOfflineScan_thenConflict` | Token đã scan server-side | conflict array populated |
| `sync_whenReplayedBatch_thenIdempotent` | Gửi cùng `syncBatchId` 2 lần | Kết quả giống nhau |
| `sync_responseDoesNotLeakRawQrToken` | Bất kỳ sync response | `qrTokenMasked` present, raw `qrToken` absent |
| `getSnapshot_whenValid_thenReturnsNoRawQr` | Download snapshot | `qrTokenMasked` + `qrTokenHash`, không có raw `qrToken` |
| `getHistory_whenStaff_thenReturnsPagedResult` | Get audit log by concertId | Paged result, đúng format |

### `CheckinControllerSecurityTest` (7 tests)

| Test | Scenario | Expected |
|---|---|---|
| `scan_whenNoToken_thenUnauthorized` | Không có Bearer token | 401 `UNAUTHORIZED` |
| `scan_whenWrongIssuer_thenInvalidToken` | JWT iss ≠ `tickefy-auth-service` | 401 `INVALID_TOKEN` |
| `scan_whenWrongAudience_thenInvalidToken` | JWT aud ≠ `tickefy-api` | 401 `INVALID_TOKEN` |
| `scan_whenExpiredToken_thenTokenExpired` | JWT đã hết hạn | 401 `TOKEN_EXPIRED` |
| `scan_whenSpoofedXUserHeader_thenIgnored` | Header `X-User-Id` bị spoof | Identity lấy từ JWT, không từ header |
| `scan_whenRoleAudience_thenForbidden` | JWT role `AUDIENCE` | 403 `FORBIDDEN` |
| `scan_whenRoleCheckinStaff_thenAllowed` | JWT role `CHECKIN_STAFF` | 200 |

## Chạy lại

```powershell
# Trong tickefy-backend/
cd services/checkin-service
mvn test

# Chỉ chạy 1 class
mvn test -Dtest=CheckinServiceTest
```
