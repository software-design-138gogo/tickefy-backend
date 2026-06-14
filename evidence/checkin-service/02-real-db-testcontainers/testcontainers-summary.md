# checkin-service Real DB Testcontainers Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/checkin-service
.\mvnw.cmd -Preal-db-test verify 2>&1 | Tee-Object -FilePath ..\..\evidence\checkin-service\02-real-db-testcontainers\mvn-real-db-verify.log
```

## Result

Status: PASS

```text
Unit/current tests: Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
Failsafe IT tests: Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Real DB Coverage

- Flyway runs clean on PostgreSQL 17 Testcontainers with schema `checkin_service`.
- Tables verified: `checkin_events`, `sync_batches`, `checkin_snapshots`, `conflicts`.
- Active schema uses `concert_id`; `event_id` is absent.
- Unique `sync_batch_id` is verified.
- Audit rows store `qr_token_masked`, not raw `qrToken`.
- Duplicate sync batch returns cached DB result and does not call the e-ticket client again.
