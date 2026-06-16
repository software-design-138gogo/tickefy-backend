# checkin-service Docker Runtime Evidence

Timestamp: 2026-06-14 14:45:27 +07:00

## Runtime

Image: `tickefy/checkin-service:evidence`

Container:

```powershell
docker run -d --name tickefy-checkin-service-evidence `
  --network local_tickefy-network `
  -p 18088:8080 `
  -e DB_HOST=tickefy-postgres `
  -e DB_NAME=tickefy `
  -e DB_SCHEMA=checkin_service `
  -e ETICKET_SERVICE_URL=http://tickefy-e-ticket-service-evidence:8080 `
  tickefy/checkin-service:evidence
```

## Result

Status: PASS

Runtime smoke used real Docker containers, real PostgreSQL, and real HTTP between `checkin-service` and `e-ticket-service`.

Verified:

- `/health` returned HTTP 200 with ApiResponse envelope.
- `POST /api/checkin/scan` returned `ACCEPTED` for a fresh ticket.
- Duplicate scan returned HTTP 200 with `DUPLICATE_REJECTED`.
- Missing auth was rejected with HTTP 401.
- Snapshot endpoint returned the same `concertId`.
- DB audit wrote masked QR only.

DB verification after runtime smoke:

```text
tickets: 1
checkin_events: 2
ACCEPTED: 1
DUPLICATE_REJECTED: 1
qr_token_masked example: cfe7****87be
```

Full log: `docker-runtime-cross-service-smoke.log`.
