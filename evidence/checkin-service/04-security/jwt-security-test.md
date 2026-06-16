# checkin-service Security Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/checkin-service
.\mvnw.cmd test
.\mvnw.cmd -Preal-db-test verify
```

## Result

Status: PASS

Security assertions are covered by `CheckinControllerSecurityTest` and `CheckinApiIT`.

## Checked Cases

- Missing JWT is rejected for protected endpoints.
- Invalid or insufficient role is rejected.
- Staff endpoints require check-in role access.
- Business scan rejection is returned as HTTP 200 with `data.result`.
- Validation/auth/system errors return non-2xx with `error.code`.
- Sync responses and cached payloads do not expose raw `qrToken`.
