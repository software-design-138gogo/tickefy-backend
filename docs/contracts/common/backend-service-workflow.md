---

title: Backend Service Implementation and Integration Workflow
status: DRAFT
version: 1.0
owner: BE Lead
reviewers:
lastUpdated: 2026-06-16

---

# Backend Service Implementation and Integration Workflow

## 1. Điều kiện bắt đầu

Trước khi bắt đầu implementation, service phải có:

* Service Specification đã được chốt.
* API Contract đã được chốt.
* Event Contract đã được chốt.
* Database ownership đã được xác định.
* Dependencies đã được xác định.
* Naming Convention và Error Catalog đã được thống nhất.

Workflow này bắt đầu từ thời điểm service đã đủ điều kiện để code.

---

## 2. Flow tổng quát

```text
Khởi động infrastructure nền
→ Tạo service skeleton
→ Đăng ký tài nguyên database và RabbitMQ
→ Viết database migration
→ Implement domain/business logic
→ Implement API
→ Implement HTTP clients
→ Implement event publisher/consumer
→ Test service độc lập
→ Dockerize service
→ Thêm service vào Docker Compose
→ Thêm route API Gateway
→ Tích hợp với service khác
→ Chạy end-to-end test
→ Push Docker image
→ Cập nhật tài liệu
```

---

## 3. Khởi động infrastructure nền

Trước khi code database hoặc messaging, chạy các infrastructure component dùng chung:

```text
PostgreSQL
Redis
RabbitMQ
MinIO
Docker network
```

Ví dụ:

```bash
cd tickefy-infrastructure/local

docker compose \
  -f docker-compose.infrastructure.yml \
  up -d
```

Hoặc dùng script của infrastructure repository:

```bash
./scripts/up.sh infra
```

Kiểm tra:

```bash
docker compose ps
```

Các dependency phải ở trạng thái healthy trước khi chạy service.

### Output

* PostgreSQL có thể kết nối.
* RabbitMQ Management UI truy cập được.
* Redis hoạt động.
* MinIO hoạt động nếu service cần file.

---

## 4. Tạo service skeleton

Tạo service tại:

```text
tickefy-backend/services/<service-name>
```

Cấu trúc tối thiểu:

```text
<service-name>/
├── src/
├── pom.xml
├── Dockerfile
├── .dockerignore
├── .env.example
└── README.md
```

Cấu hình Spring Boot phải sử dụng environment variables:

```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: <service-name>

  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}

  rabbitmq:
    host: ${RABBITMQ_HOST}
    port: ${RABBITMQ_PORT}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}
```

Quy tắc:

* Container port luôn là `8080`.
* Không hard-code `localhost`.
* Không hard-code password hoặc secret.
* Bật `/actuator/health`.
* Bật Swagger/OpenAPI.

---

## 5. Đăng ký tài nguyên infrastructure của service

Bước này thực hiện ngay sau khi tạo skeleton, không đợi đến khi code xong.

### 5.1. PostgreSQL

Thêm schema của service vào PostgreSQL init script.

Ví dụ:

```sql
CREATE SCHEMA IF NOT EXISTS order_schema;
```

Nếu mỗi service dùng database user riêng:

```sql
CREATE USER order_service_user WITH PASSWORD 'configured-by-env';

GRANT USAGE, CREATE
ON SCHEMA order_schema
TO order_service_user;
```

Infrastructure chỉ tạo:

* PostgreSQL instance.
* Schema.
* Database user.
* Permission.

Business tables được tạo bằng Flyway trong service.

### 5.2. RabbitMQ

Dựa trên Event Contract, khai báo:

* Exchange.
* Queue.
* Routing key.
* Binding.
* DLQ.

Ví dụ:

```text
Exchange: tickefy.events
Routing key: order.paid
Queue: ticket.order-paid
DLQ: ticket.order-paid.dlq
```

Cập nhật:

```text
tickefy-infrastructure/rabbitmq/definition.json
```

RabbitMQ container phải được restart hoặc reload definitions sau khi topology thay đổi.

### 5.3. Redis

Document key pattern mà service sử dụng:

```text
order:idempotency:{idempotencyKey}
reservation:{reservationId}
payment:idempotency:{idempotencyKey}
```

Mọi key tạm thời phải có TTL.

Redis không cần tạo key trước. Key được service tạo khi runtime.

### 5.4. Object Storage

Nếu service dùng file, tạo bucket hoặc prefix cần thiết.

Ví dụ:

```text
ai-bio/
vip-import/
seat-maps/
error-reports/
```

Thông tin kết nối phải truyền qua environment variables.

---

## 6. Viết database migration

Tạo migration trong service:

```text
src/main/resources/db/migration/
```

Ví dụ:

```text
V1__create_orders.sql
V2__create_order_items.sql
V3__create_order_status_history.sql
```

Flyway chịu trách nhiệm tạo:

* Tables.
* Columns.
* Indexes.
* Unique constraints.
* Check constraints.
* Foreign key trong cùng service.

Quy tắc:

* Table và column dùng `snake_case`.
* Primary key dùng UUID.
* Time dùng `TIMESTAMPTZ`.
* Money dùng `BIGINT`.
* Không tạo cross-service foreign key.

Ví dụ:

```sql
CREATE TABLE order_schema.orders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    concert_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_amount BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

`user_id` và `concert_id` là cross-service reference nên không tạo foreign key.

### Kiểm tra migration

Chạy service hoặc chạy Flyway để kiểm tra:

```bash
./mvnw spring-boot:run
```

Sau đó kiểm tra:

```sql
SELECT *
FROM flyway_schema_history;
```

Migration phải chạy được từ database rỗng.

---

## 7. Implement domain và business logic

Thứ tự khuyến nghị:

```text
Domain model
→ Repository
→ Business service/use case
→ State machine
→ Transaction
→ Error handling
```

Business logic không đặt trong controller.

Ví dụ:

```text
OrderController
→ CreateOrderUseCase
→ OrderService
→ OrderRepository
```

Xác định rõ transaction boundary.

Ví dụ:

```text
Create Order
+ Create Order Items
+ Create Status History
= cùng một database transaction
```

---

## 8. Implement API

Với mỗi endpoint:

* Tạo request DTO.
* Tạo response DTO.
* Validate input.
* Kiểm tra authentication và authorization.
* Trả common response envelope.
* Map domain exception sang error code.
* Cập nhật Swagger/OpenAPI.

Ví dụ:

```http
POST /orders
GET  /orders/{orderId}
```

Controller không expose database entity trực tiếp.

---

## 9. Implement synchronous integration

Nếu service gọi service khác qua HTTP:

1. Tạo HTTP client.
2. Cấu hình base URL bằng environment variable.
3. Cấu hình connection timeout.
4. Cấu hình read timeout.
5. Propagate `X-Request-ID`.
6. Map lỗi dependency.
7. Dùng mock khi dependency chưa chạy.

Ví dụ:

```text
Order Service
→ Inventory Service
→ Payment Service
```

Trong Docker network:

```text
http://inventory-service:8080
http://payment-service:8080
```

Không sử dụng host port:

```text
http://localhost:8088
```

trong container.

---

## 10. Implement event publisher và consumer

### Publisher

Publisher phải:

* Sử dụng Event Envelope chuẩn.
* Publish đúng exchange.
* Dùng đúng routing key.
* Publish sau khi business transaction thành công.
* Giữ nguyên `correlationId`.
* Sinh `messageId` duy nhất.

Ví dụ:

```text
Exchange: tickefy.events
Routing key: order.paid
```

### Consumer

Consumer phải:

* Listen đúng queue đã khai báo trong RabbitMQ.
* Validate `eventType` và `eventVersion`.
* Idempotent theo `messageId`.
* Chỉ ACK sau khi transaction thành công.
* Retry có giới hạn.
* Chuyển DLQ khi xử lý thất bại quá số lần cho phép.

Ví dụ:

```text
Ticket Service
→ consume queue ticket.order-paid
→ tạo tickets
→ lưu processed message
→ commit transaction
→ ACK
```

### Local event testing

Event có thể test bằng:

* RabbitMQ Management UI.
* Script publish message.
* Integration test dùng Testcontainers.
* Chạy producer service thật.

Ví dụ message test:

```json
{
  "messageId": "uuid",
  "eventType": "OrderPaid",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T10:00:00Z",
  "correlationId": "req-uuid",
  "causationId": null,
  "payload": {}
}
```

---

## 11. Test service độc lập

Chạy:

```bash
./mvnw clean package
./mvnw test
./mvnw spotless:check
```

Kiểm tra:

* Business rules.
* State transitions.
* Repository.
* API contract.
* Error codes.
* Idempotency.
* Event publisher.
* Event consumer.
* Duplicate message.
* Retry và DLQ.

Có thể sử dụng Testcontainers để chạy PostgreSQL và RabbitMQ độc lập trong integration test.

---

## 12. Dockerize service

Tạo multi-stage Dockerfile:

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build:

```bash
docker build -t tickefy/<service-name>:local .
```

Service phải chạy được khi kết nối tới infrastructure nền.

---

## 13. Thêm service vào Docker Compose

Cập nhật:

```text
tickefy-infrastructure/local/.env.example
tickefy-infrastructure/local/docker-compose.dev.yml
tickefy-infrastructure/local/docker-compose.image.yml
```

### Dev mode

```yaml
<service-name>:
  build:
    context: ../../tickefy-backend/services/<service-name>
    dockerfile: Dockerfile

  image: tickefy/<service-name>:local
  env_file:
    - .env

  environment:
    SERVER_PORT: 8080

  depends_on:
    postgres:
      condition: service_healthy
    rabbitmq:
      condition: service_healthy

  healthcheck:
    test:
      - CMD-SHELL
      - wget -qO- http://localhost:8080/actuator/health || exit 1
```

Chỉ thêm Redis, RabbitMQ hoặc MinIO vào `depends_on` khi service thực sự sử dụng chúng.

---

## 14. Thêm route API Gateway

Cập nhật API Gateway:

```yaml
- id: order-service
  uri: ${ORDER_SERVICE_URL:http://localhost:8084}
  predicates:
    - Path=/api/orders/**
```

Trong Docker Compose:

```yaml
ORDER_SERVICE_URL: http://order-service:8080
```

Frontend, admin và mobile chỉ gọi qua API Gateway:

```text
http://localhost:8080/api/orders/...
```

Direct service URL chỉ dùng để debug local.

---

## 15. Chạy integration test

Kiểm tra theo thứ tự:

```text
Service → PostgreSQL
Service → Redis
Service → RabbitMQ
Service → Object Storage
API Gateway → Service
Service → Service
Producer → RabbitMQ → Consumer
```

Sau đó test system flow mà service tham gia.

Ví dụ:

```text
Create Order
→ Reserve Inventory
→ Create Payment
→ PaymentSucceeded
→ OrderPaid
→ TicketsIssued
```

Kiểm tra:

* API response.
* Database state.
* Published event.
* Consumer result.
* Duplicate handling.
* Retry.
* DLQ.
* Logs theo `requestId`, `correlationId`, `messageId`.

---

## 16. Build và push image

Image tag dùng commit hash:

```text
commit-<short-sha>
```

Ví dụ:

```text
ghcr.io/<org>/tickefy/order-service:commit-a1b2c3d
```

Sau khi push:

* Cập nhật image tag trong `.env`.
* Chạy `docker-compose.image.yml`.
* Kiểm tra health check.
* Chạy lại integration test cơ bản.

---

## 17. Definition of Done

Service chỉ hoàn thành khi:

* [ ] Migration chạy được từ database rỗng.
* [ ] Business logic hoàn thành.
* [ ] API đúng contract.
* [ ] Event publisher/consumer đúng contract.
* [ ] Build và tests pass.
* [ ] Docker image build được.
* [ ] Dev mode chạy được.
* [ ] Image mode chạy được.
* [ ] API Gateway route hoạt động.
* [ ] PostgreSQL schema được cấu hình.
* [ ] RabbitMQ topology được cấu hình.
* [ ] Redis/Object Storage được cấu hình nếu cần.
* [ ] Sync integration hoạt động.
* [ ] Async integration hoạt động.
* [ ] End-to-end flow liên quan chạy thành công.
* [ ] README, environment variables và docs đã cập nhật.
