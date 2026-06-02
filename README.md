# tickefy-backend

Backend monorepo for the Tickefy concert ticketing system.

This repository contains Spring Boot backend service skeletons for the Tickefy microservice architecture. It is organized as a monorepo so the team can bootstrap, review, and integrate backend services with a consistent structure.

## Repository Structure

```text
tickefy-backend/
├── services/
│   ├── auth-service/
│   ├── event-service/
│   ├── inventory-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── notification-service/
│   ├── e-ticket-service/
│   ├── checkin-service/
│   ├── ai-bio-service/
│   └── csv-ingestion-service/
├── shared/
│   └── contracts/
├── docs/
├── .gitignore
├── .gitattributes
└── README.md
```

## Current Status

All 10 Spring Boot service skeletons have been bootstrapped.

Current scope:

- Common Spring Boot structure is ready for each service.
- Real business logic is not implemented yet.
- Service-specific specs and contracts must be confirmed before implementation.
- Redis, RabbitMQ, and service-specific database entities will be added later only when needed.

## Services

| Service | Port | Package | Database | Responsibility |
|---|---:|---|---|---|
| `auth-service` | 8081 | `com.tickefy.auth` | `tickefy_auth` | Authentication, users, roles, RBAC |
| `event-service` | 8082 | `com.tickefy.event` | `tickefy_event` | Concerts, artists, venues, event lifecycle, seat map metadata |
| `inventory-service` | 8083 | `com.tickefy.inventory` | `tickefy_inventory` | Ticket types, availability, reservations, anti-over-selling |
| `order-service` | 8084 | `com.tickefy.order` | `tickefy_order` | Booking/order state machine and purchase orchestration |
| `payment-service` | 8085 | `com.tickefy.payment` | `tickefy_payment` | Payment transactions, callbacks, idempotency |
| `notification-service` | 8086 | `com.tickefy.notification` | `tickefy_notification` | Email/in-app notifications, reminders, delivery logs |
| `e-ticket-service` | 8087 | `com.tickefy.eticket` | `tickefy_eticket` | E-ticket issuing, QR payloads, ticket lifecycle |
| `checkin-service` | 8088 | `com.tickefy.checkin` | `tickefy_checkin` | Online/offline check-in, sync, conflict prevention |
| `ai-bio-service` | 8089 | `com.tickefy.aibio` | `tickefy_ai_bio` | AI artist bio jobs and PDF/text processing pipeline |
| `csv-ingestion-service` | 8090 | `com.tickefy.csvingestion` | `tickefy_csv_ingestion` | CSV/VIP guest import jobs and validation |

## Tech Stack Standard

Each service follows the same backend service baseline:

- Java 21
- Spring Boot 3.x
- Maven Wrapper
- PostgreSQL
- Spring Data JPA / Hibernate
- Flyway
- Swagger/OpenAPI
- Spring Boot Actuator
- Global exception handler
- Request ID logging
- Docker multi-stage build
- Spotless formatting

## Development Rule

No spec, no code.

Before implementing a service or feature, the team must confirm or update the related spec and contract.

At minimum, each implementation should clarify:

- API request/response contract
- Error codes
- Database ownership
- Events published/consumed, if any
- Environment variables
- Health check endpoint
- Dependencies on other services

## Working With One Service

Each service is independently buildable with its own Maven Wrapper.

Example for `auth-service` on Windows PowerShell:

```powershell
cd services/auth-service
.\mvnw.cmd spotless:check
.\mvnw.cmd test
.\mvnw.cmd package
```

Example on Linux/macOS:

```bash
cd services/auth-service
./mvnw spotless:check
./mvnw test
./mvnw package
```

## Running a Service Locally

Each service has its own `.env.example`.

Example for `auth-service`:

```powershell
cd services/auth-service
Copy-Item .env.example .env
.\mvnw.cmd spring-boot:run
```

Useful endpoints for each service:

```http
GET /actuator/health
GET /health
GET /swagger-ui/index.html
GET /v3/api-docs
```

## Service READMEs

Each service owns its local development instructions, metadata, endpoints, Docker command, and TODO list in:

```text
services/[service-name]/README.md
```

The root README describes the backend monorepo. Service READMEs describe individual services.

## Shared Contracts

The folder below is reserved for shared API/event contracts if the team decides to keep common contracts in this backend repository:

```text
shared/contracts/
```

Possible future structure:

```text
shared/contracts/
├── api/
├── events/
├── errors/
└── enums/
```

Suggested contract examples:

- `shared/contracts/api/common-response.md`
- `shared/contracts/api/auth-api.md`
- `shared/contracts/events/order-paid.md`
- `shared/contracts/events/payment-succeeded.md`
- `shared/contracts/errors/error-codes.md`

Do not put business implementation code in `shared/contracts/`.

## Local Infrastructure

Full infrastructure may be managed by a separate DevOps/infrastructure repository.

For local development, this backend repo may later include a lightweight `docker-compose.yml` for dependencies such as:

- PostgreSQL
- Redis
- RabbitMQ

Recommended local development strategy:

- Run infrastructure dependencies with Docker Compose.
- Run individual services with Maven for easier debugging.
- Use mock/stub dependencies for isolated service development.
- Use real service calls only when testing integration flows.

The team still needs to decide the PostgreSQL local strategy:

1. One local database per service, or
2. One local database such as `tickefy_local` with one schema per service.

## Docker

Each service has its own `Dockerfile`.

Example:

```powershell
cd services/auth-service
docker build -t tickefy/auth-service .
docker run --rm -p 8081:8081 --env-file .env tickefy/auth-service
```

Docker image names should follow:

```text
tickefy/[service-name]
```

## Verification Status

The generated service skeletons were verified with:

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd test
.\mvnw.cmd package
```

All service skeletons should pass these checks before business logic is added.

## Commit Checklist

Before committing backend skeleton or service changes:

```text
[ ] No nested .git folder inside services/*
[ ] No target/ folder is tracked by Git
[ ] No log files are tracked by Git
[ ] Root README describes the monorepo
[ ] Service README describes only that service
[ ] Service package names are correct
[ ] Service ports and DB names are correct
[ ] Dockerfile EXPOSE matches service port
[ ] spotless:check passes
[ ] test passes
[ ] package passes
```

## Next Steps

Recommended next steps after this bootstrap commit:

1. Confirm service specs and API contracts.
2. Decide local PostgreSQL strategy.
3. Add local infrastructure setup if needed.
4. Start implementing services one by one according to ownership.
5. Add Redis/RabbitMQ only to services that need them.
6. Keep service READMEs updated as implementation grows.
