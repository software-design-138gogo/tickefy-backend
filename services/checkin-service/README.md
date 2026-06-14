# Tickefy Check-in Service

Spring Boot service responsible for staff check-in scans, audit history, offline ticket snapshot download, offline sync, and conflict/idempotency handling.

## Status

Implemented and validated for the current backend service scope.

Evidence lives under `evidence/checkin-service/` in the `tickefy-backend` repository.

## Responsibilities

- Accept online scan requests from authenticated check-in staff.
- Resolve QR tokens through protected e-ticket internal APIs.
- Keep expected scan rejections as successful API responses with stable result codes.
- Distinguish invalid QR from e-ticket downstream outage.
- Log accepted and rejected scan outcomes.
- Download real ticket snapshots from e-ticket service.
- Process offline sync batches with first-valid-server-side-wins behavior.
- Return cached sync result for duplicate `syncBatchId`.
- Expose paginated check-in history.

## Tech Stack

- Java 25
- Spring Boot 3.5
- Maven
- PostgreSQL
- Spring Data JPA / Hibernate
- Flyway
- Spring Security with local JWT validation
- RestTemplate client for e-ticket internal APIs
- Swagger/OpenAPI
- Spring Boot Actuator
- Docker multi-stage build

## Service Metadata

| Item | Value |
|---|---|
| Service name | checkin-service |
| Spring application name | checkin-service |
| Default port | 8088 |
| Database name | tickefy |
| Database schema | `checkin_service` by default |
| Java package | `com.tickefy.checkin` |
| Docker image | `tickefy/checkin-service:local` |

## Security

JWT is validated locally using `JWT_SECRET`; the service does not call auth-service to validate tokens.

All check-in APIs require `CHECKIN_STAFF` or `ADMIN`. Legacy `STAFF` is accepted for local compatibility. Role values are normalized, so both `CHECKIN_STAFF` and `ROLE_CHECKIN_STAFF` work.

Staff id is read from `SecurityContext`; it is not accepted from `X-User-Id` or request body.

The current `Authorization` header is forwarded to e-ticket internal endpoints.

## Endpoints

```http
POST /api/checkin/scan
GET /api/checkin/snapshot/{concertId}
POST /api/checkin/sync
GET /api/checkin/events/{concertId}?page={page}&size={size}&gate={gate}&staffId={staffId}&result={result}
```

Operational endpoints:

```http
GET /health
GET /actuator/health
GET /swagger-ui/index.html
GET /v3/api-docs
```

When the service starts, it logs direct links for `/health`, Swagger UI, and `/v3/api-docs`.

## Canonical Sync Contract

Request:

```json
{
  "syncBatchId": "batch-1",
  "deviceId": "device-1",
  "concertId": "concert-1",
  "gate": "A1",
  "items": [
    {
      "localId": "local-1",
      "qrToken": "opaque-token",
      "localResult": "OFFLINE_ACCEPTED",
      "scannedAt": "2026-06-13T01:00:00Z"
    }
  ]
}
```

Response:

```json
{
  "syncBatchId": "batch-1",
  "processed": 1,
  "accepted": [],
  "rejected": [],
  "conflicts": []
}
```

## Local Development

Windows PowerShell:

```powershell
Copy-Item .env.example .env
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
cp .env.example .env
./mvnw clean test
./mvnw spring-boot:run
```

Local PostgreSQL/Redis/RabbitMQ are managed by the external `tickefy-infrastructure` repository. Use localhost-based values from `.env.example` when running with Maven on the host.

## Environment Variables

See `.env.example`.

Important values:

```env
SERVER_PORT=8088
DB_NAME=tickefy
DB_SCHEMA=checkin_service
JWT_SECRET=dev-only-secret-minimum-32-chars-long
ETICKET_SERVICE_URL=http://localhost:8087
```

## Tests

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd -Dtest=CheckinServiceTest test
```

Current focused coverage includes:

- accepted, duplicate, invalid QR, wrong event, cancelled, refunded scan results
- e-ticket unavailable mapping
- snapshot from e-ticket service
- idempotent duplicate sync batch
- concurrent duplicate sync batch
- first-valid-server-side-wins conflict behavior
- history audit records

## API Smoke

From repository root:

```powershell
.\scripts\test-checkin-service-api.ps1 -BaseUrl http://localhost:8088 -Token $env:CHECKIN_STAFF_JWT
```

## Docker

```bash
docker build -t tickefy/checkin-service:local .
docker run --rm -p 8088:8088 --env-file .env tickefy/checkin-service:local
```

Docker build/run evidence is stored under `evidence/checkin-service/`.

## Deferred

- Snapshot is full-snapshot only. Add incremental snapshot support if event sizes become large.
- `@MockBean` in tests is deprecated by Spring Boot and can be migrated later.
