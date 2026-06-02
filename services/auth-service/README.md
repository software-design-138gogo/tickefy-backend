# Tickefy Auth Service

Auth & User Service for the Tickefy backend system.

## 1. Responsibilities

This service is responsible for authentication, user identity, roles, and the RBAC foundation for the Tickefy platform.

Planned responsibilities:

- User registration
- User login
- Access token / refresh token flow
- User profile endpoint
- Role and permission management
- RBAC support for customer web, admin web, mobile check-in app, and backend services

Current status:

- Spring Boot service skeleton is ready.
- Real authentication logic is not implemented yet.

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
| Service name | auth-service |
| Spring application name | auth-service |
| Default port | 8081 |
| Database name | tickefy_auth |
| Java package | com.tickefy.auth |
| Docker image | tickefy/auth-service |

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
SERVICE_NAME=auth-service
DB_NAME=tickefy
DB_SCHEMA=auth_service
```

## 7. Docker

```bash
docker build -t tickefy/auth-service .
docker run --rm -p 8081:8081 --env-file .env tickefy/auth-service
```

## 8. Development Rule

No spec, no code.

Before implementing real Auth logic, update or confirm the related spec and contract.

## 9. TODO

- Implement user registration/login.
- Define JWT/access token format.
- Define refresh token lifecycle.
- Define roles and permissions.
- Add user and role database migrations.
- Add OpenAPI contract for Auth APIs.
