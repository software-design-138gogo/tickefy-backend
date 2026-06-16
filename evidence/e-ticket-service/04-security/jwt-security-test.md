# e-ticket-service Security Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/e-ticket-service
.\mvnw.cmd test
.\mvnw.cmd -Preal-db-test verify
```

## Result

Status: PASS

Security assertions are covered by `TicketControllerSecurityTest` and `TicketApiIT`.

## Checked Cases

- Missing JWT is rejected for protected endpoints.
- Invalid or insufficient role is rejected for internal endpoints.
- Customer ticket access is owner-only.
- API responses do not expose private key material.
- Raw QR token is only returned by ticket-owner/internal ticket lookup flows, not as a leaked secret outside the ticket contract.
