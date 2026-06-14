# e-ticket-service REST Assured API Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/e-ticket-service
.\mvnw.cmd -Preal-db-test verify
```

## Result

Status: PASS. API tests are included in `TicketApiIT`.

```text
TicketApiIT: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

## Covered API Contract

- `POST /internal/tickets/issue`
- Duplicate issue returns the same ticket id and QR token.
- `GET /internal/tickets/by-token/{token}`
- `GET /internal/tickets/snapshot?concertId=...`
- `PUT /internal/tickets/{id}/check-in`
- Duplicate check-in result.
- Missing auth and role-based internal access rejection.
- Owner-only customer ticket access.
- API responses use `concertId` and UUID v4 ticket/QR values.
