# e-ticket-service Docker Runtime Evidence

Timestamp: 2026-06-14 14:45:27 +07:00

## Runtime

Image: `tickefy/e-ticket-service:evidence`

Container:

```powershell
docker run -d --name tickefy-e-ticket-service-evidence `
  --network local_tickefy-network `
  -p 18087:8080 `
  -e DB_HOST=tickefy-postgres `
  -e DB_NAME=tickefy `
  -e DB_SCHEMA=eticket_service `
  -e RABBITMQ_HOST=tickefy-rabbitmq `
  tickefy/e-ticket-service:evidence
```

## Result

Status: PASS

- `/health` returned HTTP 200 with ApiResponse envelope.
- Flyway migrated clean schema `eticket_service` on real PostgreSQL container.
- Runtime API participated in cross-service smoke:
  - `POST /internal/tickets/issue`
  - duplicate issue returns same ticket id and QR token
  - `GET /internal/tickets/by-token/{token}`
  - internal check-in through `checkin-service`

See `evidence/checkin-service/06-docker/docker-runtime-cross-service-smoke.log` for the full cross-service runtime smoke output.
