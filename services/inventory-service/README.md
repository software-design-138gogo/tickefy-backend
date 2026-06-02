# Tickefy Inventory Service

Tickefy Inventory Service for the Tickefy backend system.

## 1. Responsibilities

Responsible for ticket types, capacity, availability, reservation, release, commit, and anti-over-selling.

Current status:

- Spring Boot service skeleton is ready.
- Real business logic is not implemented yet.

## 2. Tech Stack

- Java 25 LTS
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
| Service name | inventory-service |
| Spring application name | inventory-service |
| Default port | 8083 |
| Database name | tickefy_inventory |
| Java package | com.tickefy.inventory |
| Docker image | tickefy/inventory-service |

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

Local PostgreSQL/Redis/RabbitMQ are managed by the external `tickefy-infrastructure` repository. When running services with Maven on the host machine, use localhost-based values from `.env.example`.

Important values:

```env
SERVICE_NAME=inventory-service
DB_NAME=tickefy
DB_SCHEMA=inventory_service
```

## 7. Docker

```bash
docker build -t tickefy/inventory-service .
docker run --rm -p 8083:8083 --env-file .env tickefy/inventory-service
```

## 8. Development Rule

No spec, no code.

Before implementing real service logic, update or confirm the related spec and contract.

## 9. TODO

- Confirm service API contract.
- Add service-specific database migrations.
- Add OpenAPI contract for service APIs.
- Implement service business logic after spec approval.