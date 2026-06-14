# Tickefy E-Ticket Service

Spring Boot service responsible for customer e-tickets, QR tokens, internal ticket lookup, atomic check-in state transitions, and ticket snapshots used by check-in/offline sync.

## Status

Implemented and validated for the current backend service scope.

Evidence lives under `evidence/e-ticket-service/` in the `tickefy-backend` repository.

## Responsibilities

- Issue one e-ticket per paid order item.
- Keep `orderItemId` idempotent under duplicate/concurrent issue requests.
- Generate opaque QR tokens.
- Return authenticated customer ticket list/detail.
- Enforce owner scope for customer ticket reads/cancel.
- Expose protected internal lookup/check-in/snapshot APIs for check-in service.
- Mark tickets checked in with atomic update semantics.
- Classify check-in misses as not found, duplicate, cancelled, or refunded.

## Tech Stack

- Java 25
- Spring Boot 3.5
- Maven
- PostgreSQL
- Spring Data JPA / Hibernate
- Flyway
- Spring Security with local JWT validation
- Swagger/OpenAPI
- Spring Boot Actuator
- Docker multi-stage build

## Service Metadata

| Item | Value |
|---|---|
| Service name | e-ticket-service |
| Spring application name | e-ticket-service |
| Default port | 8087 |
| Database name | tickefy |
| Database schema | `eticket_service` by default |
| Java package | `com.tickefy.eticket` |
| Docker image | `tickefy/e-ticket-service:local` |

## Security

JWT is validated locally using `JWT_SECRET`; the service does not call auth-service to validate tokens.

Customer endpoints use the authenticated subject from `SecurityContext`.

Internal endpoints require role claims:

- `POST /internal/tickets/issue`: `ADMIN` or `ORGANIZER`
- `/internal/tickets/**`: `CHECKIN_STAFF` or `ADMIN`

Legacy single `role`, `authorities`, and space-separated `scope` claims are accepted for local compatibility. Role values are normalized, so both `CHECKIN_STAFF` and `ROLE_CHECKIN_STAFF` work. Canonical tokens should use `roles`, for example:

```json
{"sub":"staff-1","roles":["CHECKIN_STAFF"]}
```

## Endpoints

Customer endpoints:

```http
GET /api/tickets
GET /api/tickets/{id}
PUT /api/tickets/{id}/cancel
```

Internal endpoints:

```http
POST /internal/tickets/issue
GET /internal/tickets/by-token/{token}
PUT /internal/tickets/{id}/check-in
GET /internal/tickets/snapshot?concertId={concertId}
```

Operational endpoints:

```http
GET /health
GET /actuator/health
GET /swagger-ui/index.html
GET /v3/api-docs
```

When the service starts, it logs direct links for `/health`, Swagger UI, and `/v3/api-docs`.

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
SERVER_PORT=8087
DB_NAME=tickefy
DB_SCHEMA=eticket_service
JWT_SECRET=dev-only-secret-minimum-32-chars-long
```

## Tests

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd -Dtest=TicketServiceTest test
```

Current focused coverage includes:

- duplicate/concurrent issue by `orderItemId`
- owner-scoped customer reads
- atomic check-in
- duplicate/cancelled/refunded/not-found classification
- snapshot response

## API Smoke

From repository root:

```powershell
.\scripts\test-e-ticket-service-api.ps1 -BaseUrl http://localhost:8087 -Token $env:CHECKIN_STAFF_JWT
```

## Docker

```bash
docker build -t tickefy/e-ticket-service:local .
docker run --rm -p 8087:8087 --env-file .env tickefy/e-ticket-service:local
```

Docker build/run evidence is stored under `evidence/e-ticket-service/`.

## Deferred

- `TicketIssuedEvent` publishing is not wired in this pass because AMQP dependencies are not present in this service yet.
- If order-service becomes the only issuer, replace `ADMIN`/`ORGANIZER` access to `/internal/tickets/issue` with a dedicated service role.
