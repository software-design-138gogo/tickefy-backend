# Tickefy Event Service

Tickefy Event Service for the Tickefy backend system.

## 1. Responsibilities

Responsible for concerts, artists, venues, event status, publish/cancel lifecycle, and seat map metadata.

Current status:

- Spring Boot service skeleton is ready.
- Real business logic is not implemented yet.

## 2. Tech Stack

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

## 3. Service Metadata

| Item | Value |
|---|---|
| Service name | event-service |
| Spring application name | event-service |
| Default port | 8082 |
| Database name | tickefy_event |
| Java package | com.tickefy.event |
| Docker image | tickefy/event-service |

## 4. Local Development

Linux/macOS:

```bash
cp .env.example .env
./mvnw test
./mvnw clean package
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
.\mvnw.cmd test
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

## 5. Useful Endpoints

```http
GET /actuator/health
GET /health
GET /swagger-ui/index.html
GET /v3/api-docs
```

## 6. Environment Variables

See `.env.example`.

Important values:

```env
SERVICE_NAME=event-service
SERVER_PORT=8082
DB_NAME=tickefy_event
```

## 7. Docker

```bash
docker build -t tickefy/event-service .
docker run --rm -p 8082:8082 --env-file .env tickefy/event-service
```

## 8. Development Rule

No spec, no code.

Before implementing real service logic, update or confirm the related spec and contract.

## 9. TODO

- Confirm service API contract.
- Add service-specific database migrations.
- Add OpenAPI contract for service APIs.
- Implement service business logic after spec approval.