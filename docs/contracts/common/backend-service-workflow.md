---
title: Backend Service Implementation and Integration Workflow
status: DRAFT
version: 1.1
owner: BE Lead
reviewers: []
lastUpdated: 2026-06-16
---

# Backend Service Implementation and Integration Workflow

> **Cách đọc file này:** Phần KHÔNG đánh dấu = thực tế đang chạy, **làm theo ngay**. Phần đánh **🔭 TARGET** = chưa áp dụng cho đồ án (đừng tưởng đã có). File mô tả đúng cách 4 service hiện tại (auth/inventory/order/e-ticket) làm — service mới phải KHỚP, không tự đi hướng khác.

## 1. Điều kiện bắt đầu

Trước khi implement, service phải có:
- Service Specification đã chốt · API Contract đã chốt · Event Contract đã chốt.
- Database ownership xác định · Dependencies xác định.
- Naming Convention + Error Catalog đã thống nhất.

---

## 2. Flow tổng quát

```text
Khởi động infrastructure nền
→ Tạo service skeleton
→ Đăng ký schema DB (init.sql) + RabbitMQ exchange nền
→ Viết database migration (Flyway)
→ Implement domain/business logic
→ Implement API
→ Implement HTTP clients (sync integration)
→ Implement event publisher/consumer (@Bean self-declare topology)
→ Test service độc lập (unit + Testcontainers IT)
→ Dockerize service
→ Thêm service vào docker-compose.dev.yml
→ (🔭 TARGET) Thêm route API Gateway
→ Tích hợp với service khác
→ Chạy end-to-end test (compose dev thật)
→ (🔭 TARGET) Push Docker image
→ Cập nhật tài liệu (service-spec)
```

---

## 3. Khởi động infrastructure nền

Compose nằm tại `tickefy-infrastructure/local/`:

```bash
cd tickefy-infrastructure/local
docker compose -f docker-compose.dev.yml up -d
# hoặc: ./scripts/up.sh dev
docker compose -f docker-compose.dev.yml ps   # chờ healthy
```

Infrastructure nền: **PostgreSQL, Redis, RabbitMQ**. (🔭 TARGET: MinIO/object storage — chưa service nào dùng; thêm khi có nhu cầu file như ai-bio/seat-map.)

Dependency phải `healthy` trước khi chạy service.

---

## 4. Tạo service skeleton

```text
tickefy-backend/services/<service-name>/
├── src/
├── pom.xml
├── Dockerfile
├── .dockerignore
├── .env.example
└── README.md
```

Spring Boot config dùng environment variables (KHÔNG hard-code host/secret):

```yaml
server:
  port: ${SERVER_PORT:8080}
spring:
  application:
    name: <service-name>
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    properties:
      hibernate.default_schema: ${DB_SCHEMA}
  # Chỉ thêm redis/rabbitmq khi service thực sự dùng:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
  rabbitmq:
    host: ${RABBITMQ_HOST}
    port: ${RABBITMQ_PORT}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}
```

Quy tắc:
- **Container port luôn `8080`** (host port khác nhau, vd 8081/8083/8084/8087 — map ở compose).
- Không hard-code `localhost`, password, secret.
- Bật `/actuator/health`. Bật Swagger/OpenAPI (`/swagger-ui/index.html`).

---

## 5. Đăng ký tài nguyên infrastructure của service

Làm ngay sau skeleton, không đợi code xong.

### 5.1. PostgreSQL — schema (database-per-service)

Thêm schema vào `tickefy-infrastructure/local/postgres/init.sql`. Quy ước tên schema = **`<service>_service`**:

```sql
CREATE SCHEMA IF NOT EXISTS order_service;
CREATE SCHEMA IF NOT EXISTS inventory_service;
CREATE SCHEMA IF NOT EXISTS auth_service;
CREATE SCHEMA IF NOT EXISTS eticket_service;
```

- **1 database dùng chung** (`DB_NAME`, vd `tickefy`), **mỗi service 1 schema riêng** (`DB_SCHEMA`). KHÔNG cross-service query schema khác.
- Dùng **chung 1 DB user** cho đồ án (không tạo user riêng mỗi service — đơn giản, đủ).
- Business tables tạo bằng **Flyway trong service** (init.sql chỉ tạo schema).

### 5.2. RabbitMQ — topology self-declare bằng @Bean (decentralized)

> **Cách nhóm dùng:** mỗi service **TỰ khai exchange/queue/binding/DLQ bằng `@Bean`** trong `RabbitMqConfig` của chính nó. Spring AMQP (`RabbitAdmin`) tự tạo trên broker khi service start. **KHÔNG khai queue/binding vào `definitions.json` tập trung.**

**Vì sao decentralized:** (1) self-contained — queue của service nào nằm trong code service đó, đọc code biết ngay nó nghe gì; (2) không sửa file infra chung → 4 người không giẫm chân/conflict; (3) service độc lập deploy.

`definitions.json` (`tickefy-infrastructure/local/rabbitmq/definitions.json`) chỉ giữ **nền dùng chung**: exchange `tickefy.exchange` (type `topic`). Service bind vào exchange này lúc start.

Quy ước (BẮT BUỘC khớp toàn hệ):
- Exchange: **`tickefy.exchange`** (topic, durable).
- Queue: **`<service>-service.<event-kebab>.queue`** (vd `inventory-service.order-paid.queue`, `ticket-service.order-paid.queue`).
- DLQ: **`<...>.queue.dlq`**.
- Routing key: **`<domain>.<event>`** (vd `order.paid`, `payment.succeeded`).

**Convention DLQ:** dùng **exchange dead-letter RIÊNG `tickefy.dlx`** (KHÔNG route DLQ qua exchange chính). Dead-letter routing key = **`<routingKey>.dlq`** (vd `order.paid` → `order.paid.dlq`).

Mẫu `@Bean` (mỗi consumer service — khớp RabbitMqConfig thật của order/inventory):
```java
@Value("${app.messaging.exchange:tickefy.exchange}") String exchange;
@Value("${app.messaging.dlx:tickefy.dlx}")           String dlx;

@Bean TopicExchange tickefyExchange() { return new TopicExchange(exchange, true, false); }
@Bean TopicExchange tickefyDlx()      { return new TopicExchange(dlx, true, false); }   // DLX riêng

@Bean Queue orderPaidQueue() {
  return QueueBuilder.durable("inventory-service.order-paid.queue")
    .deadLetterExchange(dlx)                  // fail → DLX riêng (KHÔNG exchange chính)
    .deadLetterRoutingKey("order.paid.dlq")   // <routingKey>.dlq
    .build();
}
@Bean Queue orderPaidDlq() { return QueueBuilder.durable("inventory-service.order-paid.queue.dlq").build(); }
@Bean Binding b1()   { return BindingBuilder.bind(orderPaidQueue()).to(tickefyExchange()).with("order.paid"); }
@Bean Binding bDlq() { return BindingBuilder.bind(orderPaidDlq()).to(tickefyDlx()).with("order.paid.dlq"); }
// listener factory: setDefaultRequeueRejected(false) — poison → DLQ, KHÔNG requeue vô hạn
```
> Mẫu trên là chuẩn self-declare DLQ của service Hiệp (order/inventory). e-ticket hiện chỉ khai queue, DLQ qua broker policy — khi thêm DLQ self-declare nên theo mẫu này cho đồng nhất.

> ⚠️ Queue đã tồn tại trên broker đang chạy KHÔNG đổi args được (RabbitMQ `PRECONDITION_FAILED`). Muốn thêm DLQ vào queue cũ → xóa queue cũ trước rồi để service redeclare.

### 5.3. Redis — key pattern (nếu service dùng)

Document key + TTL. Key thật đang dùng (inventory):
```text
tickefy:inventory:available:{ticketTypeId}
tickefy:inventory:user-limit:{userId}:{ticketTypeId}
tickefy:inventory:meta:{ticketTypeId}
tickefy:auth:token:blacklist:{jti}        (auth)
```
- Redis tạo key lúc runtime (không tạo trước). Key tạm phải có TTL.
- **Order KHÔNG dùng Redis** (không lock/cache). Chỉ thêm Redis vào service khi thực sự cần.

### 5.4. Object Storage — 🔭 TARGET (chưa dùng)
Chưa service nào dùng. Khi cần (ai-bio, seat-map, vip-import) → tạo bucket/prefix, kết nối qua env.

---

## 6. Database migration (Flyway)

`src/main/resources/db/migration/`, đặt tên `V<n>__<mô_tả>.sql`. Flyway tạo tables/indexes/unique/check/FK-trong-cùng-service.

Quy tắc:
- `snake_case` cho table/column. PK = **UUID**. Time = **`TIMESTAMPTZ`**. Money = **`BIGINT`**.
- **KHÔNG cross-service foreign key** (`user_id`/`concert_id`/`ticket_type_id` là cross-service ref, không FK).

```sql
CREATE TABLE order_service.orders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,        -- cross-service ref, KHÔNG FK
    concert_id UUID NOT NULL,     -- cross-service ref, KHÔNG FK
    status VARCHAR(30) NOT NULL,
    total_amount BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
Kiểm tra: chạy service từ DB rỗng → `SELECT * FROM flyway_schema_history;` phải xanh.

---

## 7. Domain & business logic

Thứ tự: Domain model → Repository → Business service/use case → State machine → Transaction → Error handling.
- Business logic KHÔNG đặt trong controller.
- Xác định rõ transaction boundary (vd: create order + order items + status history = 1 transaction).

---

## 8. Implement API

Mỗi endpoint: request/response DTO · validate input · authn/authz · trả **common response envelope** · map domain exception → error code (theo error-catalog) · cập nhật Swagger. Controller KHÔNG expose entity trực tiếp.

---

## 9. Synchronous integration (HTTP client)

Service gọi service khác: tạo HTTP client · base URL qua env · connection + read timeout · propagate `X-Request-Id` · map lỗi dependency · **dùng stub/mock khi dependency chưa chạy**.

Trong Docker network dùng **service name + container port 8080** (KHÔNG host port):
```text
http://inventory-service:8080      ✅  (KHÔNG http://localhost:8083)
```

---

## 10. Event publisher & consumer

### Event format — ENVELOPE (CHUẨN cho mọi event)

> **Chuẩn nhóm: dùng ENVELOPE cho mọi event.** Tách metadata khỏi nội dung; có `eventVersion` để nâng cấp payload không vỡ consumer.

```json
{
  "messageId": "uuid",          // dedup — BẮT BUỘC
  "eventType": "OrderPaid",     // route + chọn parser — BẮT BUỘC
  "eventVersion": "1.0",        // versioning payload — BẮT BUỘC
  "occurredAt": "2026-06-16T10:00:00Z",  // ISO-8601 UTC — BẮT BUỘC
  "payload": { /* nội dung riêng từng event */ }
}
```
(Thêm `source`/`correlationId`/`causationId` khi cần tracing — chưa bắt buộc cho đồ án.)

> ⚠️ **Legacy cần migrate:** hiện `order.*` (order.paid/payment-failed/expired) đang publish **FLAT** (field top-level, không bọc payload) để khớp consumer e-ticket lúc đầu. `payment.*` đã gần envelope (thiếu `eventVersion`). **Hướng: migrate `order.*` sang envelope** — gộp khi làm qty>1 (e-ticket đằng nào cũng sửa consumer). Service MỚI từ giờ: dùng envelope ngay.

### Publisher (qua Outbox Pattern)
- Ghi event vào bảng `outbox` **cùng transaction** với business state change.
- Drainer (`@Scheduled` polling) đọc outbox → publish đúng exchange + routing key → mark PUBLISHED.
- Publish SAU khi transaction thành công. Sinh `messageId` duy nhất. Giữ `correlationId` nếu có.

### Consumer
- `@RabbitListener` đúng queue đã self-declare (§5.2).
- Validate `eventType`/`eventVersion`. **Idempotent** (xem dưới). ACK chỉ sau khi transaction OK.
- DLQ + `setDefaultRequeueRejected(false)` — fail → DLQ, KHÔNG requeue vô hạn.

### Idempotency (thực tế hiện tại)
- **State-guard** là chính: order PAID→bỏ qua nếu đã terminal; reservation RESERVED→COMMITTED/RELEASED, đã chuyển thì skip. (Đã verified: gửi trùng ×3 không nhân đôi.)
- 🔭 TARGET: bảng `processed_messages(messageId)` để dedup tầng message — chưa cần cho happy-path, state-guard đủ. Thêm khi cần chống race 2 message cùng messageId đồng thời.

### Test event local
RabbitMQ Management UI publish tay · dev stub endpoint (vd `/dev/orders/{id}/simulate-paid`) · Testcontainers IT · chạy producer thật.

---

## 11. Test service độc lập

```bash
./mvnw clean package
./mvnw test
./mvnw -Preal-db-test verify   # IT với Testcontainers (nếu có)
```
Kiểm: business rules · state transitions · API contract · error codes · idempotency · publisher/consumer · duplicate message · DLQ. Dùng **Testcontainers** cho PostgreSQL + RabbitMQ trong IT.

---

## 12. Dockerize

Multi-stage Dockerfile, **Java 25** (project chuẩn Java 25 LTS), dùng `mvnw`:

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn ./.mvn
RUN chmod +x mvnw                                    # tránh permission denied (mvnw mất exec-bit trên Windows/git)
RUN ./mvnw -B -ntp -DskipTests dependency:go-offline
COPY src ./src
RUN ./mvnw -B -ntp clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```
> Dùng image `-jre` (KHÔNG `-alpine` nếu cần glibc). Healthcheck KHÔNG dùng `wget`/`curl` (temurin-jre không có) — xem §13.

---

## 13. Thêm vào Docker Compose

Cập nhật `.env.example` + `docker-compose.dev.yml` (+ `docker-compose.image.yml` nếu dùng image mode).

```yaml
<service-name>:
  build:
    context: ../../tickefy-backend/services/<service-name>
    dockerfile: Dockerfile
  image: tickefy/<service-name>:local
  env_file: [.env]
  environment:
    SERVER_PORT: 8080
    DB_SCHEMA: <service>_service
  ports:
    - "${<SVC>_SERVICE_HOST_PORT}:8080"
  depends_on:
    postgres: { condition: service_healthy }
    rabbitmq: { condition: service_healthy }
    # thêm redis CHỈ khi service dùng (vd inventory)
  healthcheck:
    test: ["CMD-SHELL", "bash -c 'echo >/dev/tcp/localhost/8080' 2>/dev/null || exit 1"]
```
- `depends_on` chỉ thêm redis/rabbitmq khi service THỰC SỰ dùng (vd order KHÔNG cần redis).
- **Healthcheck dùng `/dev/tcp`** (temurin-jre không có wget/curl — bài học thực tế).

---

## 14. API Gateway route — 🔭 TARGET (gateway chưa build)

> Gateway (Hoàng) **chưa build** — hiện comment-out trong compose. FE/admin/mobile tạm gọi **trực tiếp service** qua host port (hoặc dev proxy). Phần dưới là target khi gateway có.

```yaml
- id: order-service
  uri: ${ORDER_SERVICE_URL:http://order-service:8080}
  predicates: [ Path=/api/orders/** ]
```
Khi gateway live: client chỉ gọi qua gateway `http://localhost:8080/api/...`; direct URL chỉ để debug.

---

## 15. Integration test (compose dev thật)

Thứ tự kiểm: Service→PostgreSQL · Service→Redis (nếu dùng) · Service→RabbitMQ · Producer→RabbitMQ→Consumer · Service→Service (HTTP).

System flow (đã verified một phần — xem service-spec):
```text
Create Order → Reserve Inventory (HTTP) → [stub] Payment
→ PaymentSucceeded (consume) → Order PAID → publish OrderPaid (outbox→drainer)
→ Inventory commit + E-Ticket issue vé + Notification
```
Kiểm: API response · DB state · published event (RabbitMQ UI) · consumer result · duplicate handling · DLQ · logs theo `requestId`/`correlationId`/`messageId`.

---

## 16. Build & push image — 🔭 TARGET (đồ án chạy local)

Đồ án hiện chạy **build local** (`docker-compose.dev.yml`). Push registry là target:
- Tag = `commit-<short-sha>` · push ghcr · cập nhật tag trong `.env` · chạy `docker-compose.image.yml` · health check · IT lại.

---

## 17. Definition of Done

**Bắt buộc (đồ án):**
- [ ] Migration chạy từ DB rỗng.
- [ ] Business logic hoàn thành.
- [ ] API đúng contract (+ Swagger).
- [ ] Event publisher/consumer đúng contract (envelope, DLQ, idempotent).
- [ ] Build + tests pass (unit + Testcontainers IT).
- [ ] Docker image build được.
- [ ] Dev mode (`docker-compose.dev.yml`) chạy được.
- [ ] PostgreSQL schema (`<svc>_service`) cấu hình.
- [ ] RabbitMQ topology self-declare (@Bean) + DLQ.
- [ ] Redis cấu hình nếu service dùng.
- [ ] Sync + async integration hoạt động.
- [ ] End-to-end flow liên quan chạy thật (compose dev).
- [ ] README + env vars + service-spec cập nhật.

**🔭 TARGET (chưa bắt buộc đồ án):**
- [ ] Image mode (`docker-compose.image.yml`) + push ghcr.
- [ ] API Gateway route (chờ Hoàng build gateway).
- [ ] `processed_messages` dedup table (hiện state-guard đủ).
- [ ] Object storage (chưa service nào dùng).
