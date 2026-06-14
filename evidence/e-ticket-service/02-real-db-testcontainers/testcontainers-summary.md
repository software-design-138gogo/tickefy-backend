# e-ticket-service Real DB Testcontainers Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/e-ticket-service
.\mvnw.cmd -Preal-db-test verify 2>&1 | Tee-Object -FilePath ..\..\evidence\e-ticket-service\02-real-db-testcontainers\mvn-real-db-verify.log
```

## Result

Status: PASS

```text
Unit/current tests: Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
Failsafe IT tests: Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Real DB Coverage

- Flyway runs clean on PostgreSQL 17 Testcontainers with schema `eticket_service`.
- `tickets` table uses `concert_id`; no `event_id` column remains in the active schema contract.
- Unique constraints for `order_item_id` and `qr_token` are verified.
- Duplicate issue returns the existing ticket id and QR token.
- UUID v4 is asserted for ticket id and QR token.
- Concurrent issue for the same `orderItemId` leaves one row.
- Concurrent check-in for one ticket returns one `ACCEPTED` and duplicate rejects for the rest.
