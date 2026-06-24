# Tickefy Notification Service

Tickefy Notification Service for the Tickefy backend system.

## 1. Responsibilities

Responsible for email/in-app notification, reminders, delivery logs, and async notification processing.

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
| Service name | notification-service |
| Spring application name | notification-service |
| Default port | 8086 |
| Database name | tickefy_notification |
| Java package | com.tickefy.notification |
| Docker image | tickefy/notification-service |

## 4. Local Development

Linux/macOS:

```bash
cp .env.example .env
./mvnw test
./mvnw clean package
./mvnw spring-boot:run
```

Windows PowerShell:

There are 2 ways to run the application locally on Windows PowerShell:

**Option 1: Using the utility script `run-local.ps1` (Recommended)**
This script automates environment setup and starts the application:
- Checks if the `.env` file exists. If found, it automatically loads all environment variables from `.env` into the current process (process-scoped environment variables).
- Runs the Spring Boot application using `.\mvnw spring-boot:run`.

```powershell
Copy-Item .env.example .env   # Create .env file if it does not exist
.\run-local.ps1
```

**Option 2: Running manually**
```powershell
Copy-Item .env.example .env
.\mvnw.cmd test
.\mvnw.cmd clean package
# Note: Ensure that environment variables from .env are set in your PowerShell session before running the command below
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
SERVICE_NAME=notification-service
DB_NAME=tickefy
DB_SCHEMA=notification_service
```

## 7. Docker

```bash
docker build -t tickefy/notification-service .
docker run --rm -p 8086:8086 --env-file .env tickefy/notification-service
```

## 8. Development Rule

No spec, no code.

Before implementing real service logic, update or confirm the related spec and contract.

## 9. TODO

- Confirm service API contract.
- Add service-specific database migrations.
- Add OpenAPI contract for service APIs.
- Implement service business logic after spec approval.