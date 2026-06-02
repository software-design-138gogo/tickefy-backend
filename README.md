# tickefy-backend

Backend monorepo for the Tickefy concert ticketing system.

This repository contains backend service source code, shared contracts, and backend-level documentation. It is designed as a monorepo so the team can bootstrap, review, and integrate multiple Spring Boot microservices consistently during the Software Design project.

## 1. Purpose

`tickefy-backend` is responsible for backend business services such as authentication, concert management, ticket inventory, order booking, payment, e-ticket issuing, check-in, notifications, AI Bio, and CSV/VIP guest ingestion.

This repository does **not** own the full infrastructure setup. API Gateway, Docker Compose for the whole system, Redis, RabbitMQ, PostgreSQL, MinIO, and cloud/deployment configuration may be managed by the DevOps/infrastructure repository.

## 2. Repository Structure

```text
tickefy-backend/
├── services/
│   └── auth-service/
├── shared/
│   └── contracts/
├── docs/
│   └── AUTH_SERVICE_BOOTSTRAP_REPORT.md
├── .gitignore
└── README.md
```

Current state:

- `services/auth-service/` has been bootstrapped from the Spring Boot service template.
- Other service folders will be added after the first service skeleton is verified and the copy/rename process is stable.

## 3. Planned Services

The backend architecture is planned around 10 services:

| Service | Purpose |
|---|---|
| `auth-service` | Authentication, users, roles, and RBAC |
| `event-service` | Concerts, artists, venues, event status, seat map metadata |
| `inventory-service` | Ticket types, availability, reservation, anti-over-selling |
| `order-service` | Booking/order state machine and purchase orchestration |
| `payment-service` | Payment transaction, callbacks, idempotency, provider integration |
| `notification-service` | Email, in-app notification, reminders |
| `e-ticket-service` | Ticket issuing, QR payload, ticket lifecycle |
| `checkin-service` | Online/offline check-in, sync, conflict detection |
| `ai-bio-service` | PDF extraction and AI-generated artist bio |
| `csv-ingestion-service` | CSV/VIP guest import jobs and validation |

## 4. Current Status

The first bootstrapped service is:

```text
services/auth-service
```

Current verification status:

```text
spotless:check  -> BUILD SUCCESS
test            -> BUILD SUCCESS
package         -> BUILD SUCCESS
```

`auth-service` is currently a Spring Boot skeleton only. Real Auth business logic has not been implemented yet.

## 5. Tech Stack Standard

Each backend service should follow the common Spring Boot service template:

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

## 6. Development Rule

No spec, no code.

Before implementing a service or feature, the related spec and contract should be confirmed or updated first.

At minimum, a service implementation should clarify:

- API request/response contract
- Error codes
- Database ownership
- Events published/consumed, if any
- Environment variables
- Health check endpoint
- Dependencies on other services

## 7. Working With One Service

Example for `auth-service`.

### Windows PowerShell

```powershell
cd services/auth-service
.\mvnw.cmd spotless:check
.\mvnw.cmd test
.\mvnw.cmd package
```

### Linux/macOS

```bash
cd services/auth-service
./mvnw spotless:check
./mvnw test
./mvnw package
```

## 8. Running a Service Locally

Each service should provide its own `.env.example`.

For `auth-service`:

```powershell
cd services/auth-service
Copy-Item .env.example .env
.\mvnw.cmd spring-boot:run
```

Useful endpoints:

```http
GET /actuator/health
GET /health
GET /swagger-ui/index.html
GET /v3/api-docs
```

## 9. Service README

Each service should have its own README for local development and service-specific notes.

Current service README:

```text
services/auth-service/README.md
```

The root README explains the backend monorepo. Service README files explain individual service details.

## 10. Shared Contracts

The folder below is reserved for shared API/event contracts if the team decides to keep common contracts in the backend monorepo:

```text
shared/contracts/
```

Possible future contents:

```text
shared/contracts/
├── api/
├── events/
└── errors/
```

Do not put business implementation code in `shared/contracts/`.

## 11. Documentation

Current backend bootstrap report:

```text
docs/AUTH_SERVICE_BOOTSTRAP_REPORT.md
```

Future docs may include:

```text
docs/
├── workflows/
├── service-contracts/
├── integration-notes/
└── runbooks/
```

## 12. Next Steps

Recommended next steps:

1. Commit the current `auth-service` skeleton.
2. Use the verified copy/rename process to bootstrap the remaining service skeletons.
3. Add service-specific ports, database names, and README metadata for each service.
4. Add backend workflow docs if needed.
5. Implement real business logic only after specs/contracts are confirmed.

## 13. Commit Checklist

Before committing backend skeleton changes:

```text
[ ] No nested .git folder inside services/*
[ ] No target/ folder is tracked by Git
[ ] No log files are tracked by Git
[ ] README files are in the correct scope
[ ] Service package names are correct
[ ] Service ports and DB names are correct
[ ] spotless:check passes
[ ] test passes
[ ] package passes
```
