# checkin-service REST Assured API Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/checkin-service
.\mvnw.cmd -Preal-db-test verify
```

## Result

Status: PASS. API tests are included in `CheckinApiIT`.

```text
CheckinApiIT: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

## Covered API Contract

- `POST /api/checkin/scan`
- Valid scan returns HTTP 200 with `data.result = ACCEPTED`.
- Duplicate scan returns HTTP 200 with `data.result = DUPLICATE_REJECTED`.
- Wrong concert returns HTTP 200 with `data.result = WRONG_CONCERT`.
- Cancelled ticket returns HTTP 200 with `data.result = CANCELLED_TICKET`.
- Refunded ticket returns HTTP 200 with `data.result = REFUNDED_TICKET`.
- Validation/auth/system errors return non-2xx with `error.code`.
- `GET /api/checkin/snapshot/{concertId}` returns `concertId`.
- `POST /api/checkin/sync` returns `qrTokenMasked` and does not leak raw QR tokens.
