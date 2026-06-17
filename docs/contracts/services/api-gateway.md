---
title: Service Specification - api-gateway
status: PROPOSED
version: 1.0
owner: Hoàng
reviewers: [BE Lead, Auth Service, Frontend, Mobile]
lastUpdated: 2026-06-17
-----------------------

# Service Specification — `api-gateway`

> `api-gateway` là infrastructure component, không phải business service. Gateway là entry point duy nhất cho Web, Admin Web và Mobile khi hệ thống chạy tích hợp.

## 1. Identity

| Item                        | Value                                  |
| --------------------------- | -------------------------------------- |
| Component name              | `api-gateway`                          |
| Owner                       | Hoàng                                  |
| Repository                  | `tickefy-backend/services/api-gateway` |
| Technology                  | Spring Cloud Gateway WebFlux           |
| Runtime                     | Spring Boot, reactive/non-blocking     |
| Public port                 | 8080                                   |
| Container port              | 8080                                   |
| Public base path            | `/api/**`                              |
| Health check                | `/actuator/health`                     |
| Database schema             | None                                   |
| RabbitMQ                    | None                                   |
| Swagger/OpenAPI aggregation | Deferred, không làm trong MVP          |

## 2. Architecture decision

API Gateway được triển khai bằng:

```text
Spring Cloud Gateway
Spring WebFlux
Spring Security Reactive
OAuth2 Resource Server JWT
Reactive Redis
Spring Boot Actuator
Micrometer
```

Các quyết định đã chốt:

* Chỉ xây một Gateway chung cho Web, Admin Web và Mobile trong MVP.
* Chưa tách BFF riêng cho từng client.
* Không sử dụng Eureka hoặc Consul trong local development.
* Gateway lấy downstream URL từ environment variables.
* Trong Docker Compose, Gateway gọi service bằng Docker service name và container port `8080`.
* Gateway không có database.
* Gateway không publish hoặc consume business event.
* Redis chỉ được sử dụng cho rate limiting.
* Gateway có thể scale nhiều replica; không lưu session hoặc state cục bộ.

## 3. Responsibilities

### Gateway chịu trách nhiệm

* Là entry point duy nhất cho client.
* Route request đến đúng downstream service.
* Rewrite path khi external path khác controller path hiện tại.
* Phân loại public endpoint và protected endpoint.
* Verify JWT trên protected endpoint.
* Reject sớm request thiếu token hoặc token không hợp lệ.
* Forward nguyên `Authorization: Bearer <access-token>`.
* Tạo hoặc propagate `X-Request-ID`.
* Cấu hình CORS tập trung.
* Rate limiting bằng Redis.
* Cấu hình connection timeout và response timeout.
* Hỗ trợ multipart upload.
* Hỗ trợ Server-Sent Events.
* Thêm security response headers.
* Ghi access log và metrics theo route.
* Trả common error envelope cho lỗi do chính Gateway tạo ra.

### Gateway không chịu trách nhiệm

* Không xử lý business logic.
* Không query database của bất kỳ service nào.
* Không điều phối saga mua vé hoặc thanh toán.
* Không gọi nhiều service để aggregate business response trong MVP.
* Không kiểm tra ownership của concert, order, ticket hoặc notification.
* Không quyết định role chi tiết của operation.
* Không thay thế authorization tại downstream service.
* Không publish hoặc consume RabbitMQ event.
* Không expose `/internal/**` cho client.
* Không tự retry các command thay đổi dữ liệu.
* Không chuyển đổi hoặc wrap lại response thành công của downstream service.
* Không dùng `X-User-ID` hoặc `X-User-Roles` làm nguồn xác thực.

## 4. Data ownership

API Gateway không sở hữu business data.

### Tables owned

| Table | Purpose                   |
| ----- | ------------------------- |
| None  | Gateway không có database |

### Runtime state

| State               | Storage                   | Purpose                                            |
| ------------------- | ------------------------- | -------------------------------------------------- |
| Rate-limit counters | Redis                     | Giới hạn lưu lượng theo IP hoặc authenticated user |
| Route configuration | Application configuration | Xác định downstream URI, predicate và filter       |
| JWT public key      | Mounted secret/classpath  | Verify access token                                |

### Invariants

* Gateway phải stateless.
* Không lưu access token hoặc refresh token.
* Không lưu request body.
* Không lưu user profile.
* Không có cross-service foreign key.
* Redis rate-limit data không phải source of truth nghiệp vụ.

## 5. Dependencies

### Synchronous downstream dependencies

| Service                 | Base URL trong Docker               | Purpose                                           |
| ----------------------- | ----------------------------------- | ------------------------------------------------- |
| `auth-service`          | `http://auth-service:8080`          | Register, login, refresh, logout, user management |
| `event-service`         | `http://event-service:8080`         | Public/admin concert APIs                         |
| `inventory-service`     | `http://inventory-service:8080`     | Ticket types và availability                      |
| `order-service`         | `http://order-service:8080`         | Tạo và xem order                                  |
| `payment-service`       | `http://payment-service:8080`       | Payment callback và admin refund                  |
| `ticket-service`        | `http://ticket-service:8080`        | Ticket APIs                                       |
| `checkin-service`       | `http://checkin-service:8080`       | Online/offline check-in APIs                      |
| `notification-service`  | `http://notification-service:8080`  | Notification history, device và SSE               |
| `ai-bio-service`        | `http://ai-bio-service:8080`        | AI concert introduction jobs                      |
| `csv-ingestion-service` | `http://csv-ingestion-service:8080` | VIP CSV import jobs                               |

### Infrastructure dependencies

| Dependency     | Required | Purpose                                |
| -------------- | -------: | -------------------------------------- |
| Redis          |      Yes | Distributed rate limiting              |
| JWT public key |      Yes | Verify RS256 access token              |
| PostgreSQL     |       No | Gateway không sở hữu data              |
| RabbitMQ       |       No | Gateway không xử lý business event     |
| Object Storage |       No | File được proxy đến service sở hữu     |
| Eureka/Consul  |       No | Docker DNS + environment URL trong MVP |

## 6. Route contract

Gateway chỉ expose các route public chính thức. Không tạo route cho `/internal/**`.

| Route ID               | External path              | Downstream service      | Downstream path    | Rewrite          |
| ---------------------- | -------------------------- | ----------------------- | ------------------ | ---------------- |
| `auth-route`           | `/api/auth/**`             | `auth-service`          | `/auth/**`         | Bỏ prefix `/api` |
| `concert-public-route` | `/api/concerts/**`         | `event-service`         | Giữ nguyên         | No               |
| `concert-admin-route`  | `/api/admin/concerts/**`   | `event-service`         | Giữ nguyên         | No               |
| `inventory-route`      | `/api/inventory/**`        | `inventory-service`     | Giữ nguyên         | No               |
| `order-route`          | `/api/orders/**`           | `order-service`         | `/orders/**`       | Bỏ prefix `/api` |
| `my-orders-route`      | `/api/users/me/orders`     | `order-service`         | `/users/me/orders` | Bỏ prefix `/api` |
| `payment-route`        | `/api/payments/**`         | `payment-service`       | Giữ nguyên         | No               |
| `payment-admin-route`  | `/api/admin/payments/**`   | `payment-service`       | Giữ nguyên         | No               |
| `ticket-route`         | `/api/tickets/**`          | `ticket-service`        | Giữ nguyên         | No               |
| `checkin-route`        | `/api/checkins/**`         | `checkin-service`       | Giữ nguyên         | No               |
| `notification-route`   | `/api/notifications/**`    | `notification-service`  | Giữ nguyên         | No               |
| `ai-bio-route`         | `/api/ai-bio/**`           | `ai-bio-service`        | Giữ nguyên         | No               |
| `csv-import-route`     | `/api/admin/csv-import/**` | `csv-ingestion-service` | Giữ nguyên         | No               |

### Route exclusions

Các path sau không được expose:

```text
/internal/**
/actuator/** của downstream service
/swagger-ui/** của downstream service
/v3/api-docs/** của downstream service
/dev/**
```

Gateway chỉ expose health check của chính nó:

```text
GET /actuator/health
```

## 7. Authentication policy

### Public endpoints

| Method    | Path                      | Notes                                                  |
| --------- | ------------------------- | ------------------------------------------------------ |
| `OPTIONS` | `/**`                     | CORS preflight                                         |
| `GET`     | `/actuator/health`        | Gateway health                                         |
| `POST`    | `/api/auth/register`      | Public                                                 |
| `POST`    | `/api/auth/login`         | Public                                                 |
| `POST`    | `/api/auth/refresh-token` | Public, sử dụng refresh cookie/body                    |
| `GET`     | `/api/concerts/**`        | Public concert browsing                                |
| `GET`     | `/api/inventory/**`       | Public ticket type và availability reads               |
| `POST`    | `/api/payments/callback`  | Provider webhook, verify signature tại Payment Service |

Tất cả endpoint còn lại yêu cầu access token.

### JWT verification

Gateway phải verify:

```text
Algorithm: RS256
Signature: valid
exp: chưa hết hạn
iss: tickefy-auth-service
aud: tickefy-api
```

Claims tối thiểu:

```json
{
  "iss": "tickefy-auth-service",
  "aud": "tickefy-api",
  "sub": "user-uuid",
  "roles": ["AUDIENCE"],
  "jti": "token-uuid",
  "iat": 0,
  "exp": 0
}
```

### Authorization boundary

Gateway chỉ xác định:

```text
Endpoint public
hoặc
Endpoint yêu cầu authenticated user
```

Downstream service vẫn phải:

* Verify lại JWT bằng RS256 public key.
* Lấy identity từ JWT `sub`.
* Kiểm tra role.
* Kiểm tra ownership.
* Kiểm tra permission theo concert/gate/resource.
* Áp dụng business authorization.

Gateway không thực hiện role hoặc ownership authorization thay cho service.

### Token revocation

Trong MVP, Gateway chỉ verify signature, `exp`, `iss` và `aud`.

Gateway chưa kiểm tra Redis blacklist của Auth Service. Access token đã logout có thể tiếp tục hợp lệ đến khi hết TTL, tối đa theo Auth Contract.

Blacklist check tại Gateway là target sau MVP.

## 8. Header contract

### Request headers được propagate

```text
Authorization
Content-Type
Accept
Cookie
Idempotency-Key
X-Request-ID
User-Agent
```

### Response headers được propagate

```text
Content-Type
Set-Cookie
X-Request-ID
Retry-After
Location
Cache-Control
```

### Request ID

Quy tắc:

1. Nhận `X-Request-ID` từ client nếu hợp lệ.
2. Nếu thiếu hoặc không hợp lệ, Gateway sinh UUID mới.
3. Gắn request ID vào request downstream.
4. Gắn request ID vào response header.
5. Gắn request ID vào access log và MDC/reactive context.
6. Downstream response body phải sử dụng cùng request ID.

Request ID không phải authentication data.

### Identity headers

Client không được phép quyết định identity qua:

```text
X-User-ID
X-User-Roles
```

Gateway phải xóa các header này nếu client gửi.

Trong MVP, Gateway không cần inject lại `X-User-ID` hoặc `X-User-Roles`. Downstream lấy identity từ JWT đã verify.

Nếu triển khai identity headers trong tương lai:

* Gateway phải overwrite bằng giá trị lấy từ JWT.
* Downstream chỉ sử dụng cho logging/debug.
* Downstream không sử dụng làm nguồn authorization duy nhất.

## 9. CORS policy

Gateway sở hữu CORS tập trung cho traffic qua Gateway.

Cấu hình:

```text
Allowed origins: lấy từ CORS_ALLOWED_ORIGINS
Allow credentials: true
Allowed methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
Allowed headers:
  Authorization
  Content-Type
  Accept
  X-Request-ID
  Idempotency-Key
Exposed headers:
  X-Request-ID
  Retry-After
  Location
Max age: cấu hình qua environment
```

Quy tắc:

* Không dùng `*` khi `allowCredentials=true`.
* Origins phải khai báo rõ qua environment.
* Gateway phải forward `Cookie` và `Set-Cookie` cho refresh flow.
* Downstream CORS có thể tắt khi chỉ được gọi qua Gateway.
* Direct-development mode có thể giữ CORS riêng tại service nếu cần.

## 10. Rate limiting

Gateway sử dụng Redis-backed token bucket rate limiting.

### Key selection

| Request type                      | Rate-limit key                                        |
| --------------------------------- | ----------------------------------------------------- |
| Anonymous/public request          | Client IP                                             |
| Authenticated request             | JWT `sub`                                             |
| Token không parse được            | Client IP                                             |
| Request sau trusted reverse proxy | Client IP đã resolve theo trusted proxy configuration |

Không mặc định tin mọi giá trị `X-Forwarded-For`.

### Rate-limit profiles

| Profile                 | Routes                             |
| ----------------------- | ---------------------------------- |
| `public-read`           | Public concert và availability GET |
| `auth-sensitive`        | Login, register, refresh           |
| `order-command`         | Tạo và cancel order                |
| `checkin-command`       | Scan và offline sync               |
| `upload-command`        | AI Bio và CSV upload/retry         |
| `default-authenticated` | Các protected endpoint còn lại     |

Ngưỡng cụ thể là operational configuration, không phải business contract. Ngưỡng phải cấu hình qua environment và điều chỉnh sau load test.

Khi vượt giới hạn:

```text
HTTP 429
error.code = RATE_LIMIT_EXCEEDED
Retry-After header phải được trả khi xác định được
```

Redis rate limiting chỉ là lớp bảo vệ traffic. Nó không thay thế:

* Order idempotency.
* Inventory atomic reservation.
* Per-user ticket limit.
* Payment idempotency.
* Check-in idempotency.

### Redis failure policy

Nếu Redis tạm unavailable:

* Gateway fail-open cho rate limiting.
* Request vẫn được route.
* Ghi structured warning log.
* Tăng metric rate-limit dependency failure.
* Không làm toàn hệ thống ngừng hoạt động chỉ vì rate limiter lỗi.

## 11. Timeout and retry policy

### Initial timeout configuration

| Route type       | Connect timeout |    Response timeout |
| ---------------- | --------------: | ------------------: |
| Default REST     |          2 giây |             15 giây |
| Check-in command |          2 giây |              5 giây |
| Multipart upload |          2 giây |             60 giây |
| Notification SSE |          2 giây | Disabled/long-lived |

Các giá trị phải cấu hình được qua environment.

### Retry

Gateway không tự động retry trong MVP.

Đặc biệt không retry:

```text
POST
PUT
PATCH
DELETE
payment callback
order creation
check-in scan
file upload
```

Lý do:

* Có thể tạo duplicate side effect.
* Gateway không sở hữu business idempotency state.
* Retry an toàn phải được service hoặc client quyết định dựa trên idempotency contract.

### Circuit breaker

Không triển khai Gateway-level circuit breaker trong MVP.

Circuit breaker cho external provider thuộc service sở hữu integration, ví dụ:

* Payment Service đối với payment provider.
* AI Bio Service đối với AI Provider.
* Notification Service đối với SMTP/FCM.

## 12. Error handling

Gateway chỉ tạo common error envelope cho lỗi xảy ra trước hoặc trong quá trình proxy.

Gateway không sửa response body thành công hoặc lỗi nghiệp vụ do downstream trả về.

### Gateway-generated errors

| HTTP | Code                    | Situation                                           |
| ---: | ----------------------- | --------------------------------------------------- |
|  401 | `UNAUTHORIZED`          | Protected endpoint thiếu access token               |
|  401 | `INVALID_TOKEN`         | Token sai chữ ký, hết hạn, sai issuer hoặc audience |
|  404 | `RESOURCE_NOT_FOUND`    | Không có public route phù hợp                       |
|  429 | `RATE_LIMIT_EXCEEDED`   | Vượt rate limit                                     |
|  503 | `SERVICE_UNAVAILABLE`   | Không kết nối được hoặc timeout downstream          |
|  500 | `INTERNAL_SERVER_ERROR` | Gateway gặp lỗi chưa xử lý                          |

Canonical error response:

```json
{
  "success": false,
  "data": null,
  "error": {
    "httpStatus": 401,
    "code": "INVALID_TOKEN",
    "message": "Phiên không hợp lệ.",
    "details": {}
  },
  "requestId": "req-uuid",
  "timestamp": "2026-06-17T10:00:00Z"
}
```

### Downstream response policy

Gateway phải preserve:

```text
HTTP status
response body
Content-Type
Set-Cookie
X-Request-ID
Retry-After
```

Gateway không:

* Chuyển lỗi downstream thành `200`.
* Đổi `error.code`.
* Parse business response.
* Wrap response thêm một lớp envelope.
* Thay đổi check-in business result.

## 13. Special route handling

### 13.1. Auth refresh cookie

Route:

```text
POST /api/auth/refresh-token
```

Requirements:

* Forward `Cookie`.
* Forward request body nếu client fallback dùng body.
* Forward `Set-Cookie` từ Auth Service.
* CORS phải bật credentials.
* Không cache response.

### 13.2. Payment callback

Route:

```text
POST /api/payments/callback
```

Requirements:

* Không yêu cầu Tickefy JWT.
* Không thay đổi callback body.
* Không thay đổi provider signature headers.
* Không retry callback.
* Payment Service tự verify provider signature.
* Rate limit theo provider IP hoặc callback profile nếu cần.

### 13.3. Notification SSE

Route:

```text
GET /api/notifications/stream
```

Requirements:

* Yêu cầu authenticated user.
* Preserve `text/event-stream`.
* Không buffer toàn bộ response.
* Không cache.
* Không retry.
* Không áp dụng response timeout thông thường.
* Không modify response body.
* Notification Service phải gửi heartbeat định kỳ.

### 13.4. AI Bio multipart

Route:

```text
POST /api/ai-bio/concerts/{concertId}/jobs
```

Requirements:

* Yêu cầu JWT.
* Forward `Idempotency-Key`.
* Forward multipart stream.
* Không log file content.
* Gateway transport limit phải lớn hơn service limit 25 MB tổng.
* Service vẫn chịu trách nhiệm validate 1–5 PDF, MIME, magic bytes và giới hạn chính xác.

Gateway transport hard limit đề xuất:

```text
30 MB
```

### 13.5. CSV multipart

Route:

```text
POST /api/admin/csv-import
```

Requirements:

* Yêu cầu JWT.
* Forward multipart stream.
* Không log CSV content.
* Gateway transport limit phải lớn hơn service limit 10 MB.
* CSV Service vẫn validate encoding, header, content và ownership.

Gateway transport hard limit đề xuất:

```text
12 MB
```

## 14. Events

### Events published

| Event | Status                               |
| ----- | ------------------------------------ |
| None  | Gateway không publish business event |

### Events consumed

| Event | Status                               |
| ----- | ------------------------------------ |
| None  | Gateway không consume business event |

Gateway request log hoặc metric không được phát dưới dạng integration event trên RabbitMQ.

## 15. Security hardening

Gateway phải:

* Không log JWT.
* Không log refresh token.
* Không log Cookie.
* Không log payment signature.
* Không log multipart body.
* Không log raw query parameter chứa secret.
* Xóa client-provided `X-User-ID` và `X-User-Roles`.
* Validate request ID format và độ dài.
* Chỉ trust forwarded headers từ configured proxy.
* Tắt detailed error stack trace.
* Dùng HTTPS ở production.
* Bật HSTS ở production.
* Thêm `X-Content-Type-Options: nosniff`.
* Cấu hình frame options phù hợp.
* Cấu hình Referrer Policy.
* Không expose actuator endpoints ngoài health cần thiết.
* Không commit production public/private key pair tùy tiện; Gateway chỉ cần public key.

## 16. Environment variables

| Variable                                    | Required | Example                             | Description                 |
| ------------------------------------------- | -------: | ----------------------------------- | --------------------------- |
| `SERVER_PORT`                               |      Yes | `8080`                              | Gateway port                |
| `SPRING_PROFILES_ACTIVE`                    |      Yes | `docker`                            | Runtime profile             |
| `AUTH_SERVICE_URL`                          |      Yes | `http://auth-service:8080`          | Auth URL                    |
| `EVENT_SERVICE_URL`                         |      Yes | `http://event-service:8080`         | Event URL                   |
| `INVENTORY_SERVICE_URL`                     |      Yes | `http://inventory-service:8080`     | Inventory URL               |
| `ORDER_SERVICE_URL`                         |      Yes | `http://order-service:8080`         | Order URL                   |
| `PAYMENT_SERVICE_URL`                       |      Yes | `http://payment-service:8080`       | Payment URL                 |
| `TICKET_SERVICE_URL`                        |      Yes | `http://ticket-service:8080`        | Ticket URL                  |
| `CHECKIN_SERVICE_URL`                       |      Yes | `http://checkin-service:8080`       | Check-in URL                |
| `NOTIFICATION_SERVICE_URL`                  |      Yes | `http://notification-service:8080`  | Notification URL            |
| `AI_BIO_SERVICE_URL`                        |      Yes | `http://ai-bio-service:8080`        | AI Bio URL                  |
| `CSV_INGESTION_SERVICE_URL`                 |      Yes | `http://csv-ingestion-service:8080` | CSV URL                     |
| `REDIS_HOST`                                |      Yes | `redis`                             | Rate-limit Redis            |
| `REDIS_PORT`                                |      Yes | `6379`                              | Redis port                  |
| `REDIS_PASSWORD`                            |       No | `***`                               | Redis password              |
| `JWT_PUBLIC_KEY_PATH`                       |      Yes | `/keys/public.pem`                  | RS256 public key            |
| `JWT_ISSUER`                                |      Yes | `tickefy-auth-service`              | Expected issuer             |
| `JWT_AUDIENCE`                              |      Yes | `tickefy-api`                       | Expected audience           |
| `CORS_ALLOWED_ORIGINS`                      |      Yes | `http://localhost:3000`             | CSV list of allowed origins |
| `CORS_MAX_AGE_SECONDS`                      |       No | `3600`                              | Preflight cache             |
| `GATEWAY_CONNECT_TIMEOUT_MS`                |       No | `2000`                              | Global connect timeout      |
| `GATEWAY_RESPONSE_TIMEOUT_MS`               |       No | `15000`                             | Default response timeout    |
| `GATEWAY_UPLOAD_TIMEOUT_MS`                 |       No | `60000`                             | Upload timeout              |
| `AI_BIO_GATEWAY_MAX_BYTES`                  |       No | `31457280`                          | AI transport hard limit     |
| `CSV_GATEWAY_MAX_BYTES`                     |       No | `12582912`                          | CSV transport hard limit    |
| `GATEWAY_TRUSTED_PROXIES`                   |       No | —                                   | Trusted reverse proxies     |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` |       No | `health,info,prometheus`            | Actuator exposure           |

Rate-limit profile values phải có environment variables riêng và không hard-code trong source.

## 17. Observability

### Required logs

Mỗi completed request log tối thiểu:

```text
requestId
routeId
method
path
status
durationMs
clientIp
userId nếu JWT hợp lệ
userAgent
rateLimitResult
downstreamService
```

Không log:

```text
Authorization
Cookie
Set-Cookie
password
JWT claims đầy đủ
request body
PDF/CSV content
payment signature
raw QR token
```

### Metrics

Required metrics:

```text
gateway_requests_total{route,status}
gateway_request_duration_seconds{route}
gateway_auth_rejections_total{reason}
gateway_rate_limit_rejections_total{route}
gateway_rate_limit_dependency_failures_total
gateway_downstream_failures_total{service,reason}
gateway_active_sse_connections
```

### Health

Health check phải phản ánh:

* Gateway process đang chạy.
* Route configuration được load.
* JWT public key đọc được.
* Redis status được report.

Redis unavailable không được làm Gateway ngừng route request nếu fail-open policy đang áp dụng.

## 18. Failure scenarios

| Scenario                                        | Expected behavior                                                   |
| ----------------------------------------------- | ------------------------------------------------------------------- |
| Không tìm thấy route                            | `404 RESOURCE_NOT_FOUND`                                            |
| Protected endpoint thiếu token                  | `401 UNAUTHORIZED`                                                  |
| Token hết hạn/sai signature/sai issuer/audience | `401 INVALID_TOKEN`                                                 |
| Downstream connection refused                   | `503 SERVICE_UNAVAILABLE`                                           |
| Downstream response timeout                     | `503 SERVICE_UNAVAILABLE`                                           |
| Rate limit exceeded                             | `429 RATE_LIMIT_EXCEEDED` + `Retry-After`                           |
| Redis rate limiter unavailable                  | Fail-open, route request, log warning                               |
| Client giả `X-User-ID`                          | Header bị xóa                                                       |
| Client gọi `/internal/**`                       | Không match route, trả 404                                          |
| SSE chạy lâu                                    | Connection giữ mở, không dùng default response timeout              |
| Gateway restart                                 | Không mất business data; client có thể retry theo endpoint contract |
| Invalid CORS origin                             | Browser request bị từ chối tại Gateway                              |
| JWT public key không load được                  | Gateway fail startup/readiness                                      |

## 19. Integration acceptance criteria

* [ ] Gateway build thành công.
* [ ] Gateway chạy ở container port `8080`.
* [ ] `/actuator/health` hoạt động.
* [ ] Tất cả service URL lấy từ environment.
* [ ] Không có database hoặc RabbitMQ dependency.
* [ ] `/api/auth/login` route đúng sang `/auth/login`.
* [ ] `/api/orders` route đúng sang `/orders`.
* [ ] `/api/users/me/orders` route đúng sang `/users/me/orders`.
* [ ] Public concert GET không yêu cầu token.
* [ ] Protected order API thiếu token trả `401 UNAUTHORIZED`.
* [ ] Token sai trả `401 INVALID_TOKEN`.
* [ ] Gateway forward nguyên `Authorization`.
* [ ] Downstream service vẫn verify lại JWT.
* [ ] Client-provided `X-User-ID` và `X-User-Roles` bị xóa.
* [ ] `X-Request-ID` được tạo/propagate/echo.
* [ ] CORS credentials hoạt động với refresh cookie.
* [ ] Rate limiting trả `429 RATE_LIMIT_EXCEEDED`.
* [ ] Redis down không làm toàn bộ API ngừng hoạt động.
* [ ] `/internal/**` không thể gọi qua Gateway.
* [ ] Payment callback không yêu cầu Tickefy JWT.
* [ ] Payment signature headers được preserve.
* [ ] AI Bio multipart upload được proxy.
* [ ] CSV multipart upload được proxy.
* [ ] Notification SSE giữ connection và nhận heartbeat.
* [ ] Gateway không sửa downstream success/error envelope.
* [ ] Access log không chứa JWT, Cookie hoặc request body.

## 20. Out of scope

Các nội dung không làm trong MVP:

* Tách BFF riêng cho Web/Admin/Mobile.
* Service discovery bằng Eureka hoặc Consul.
* Dynamic route configuration từ database.
* API composition hoặc response aggregation.
* Gateway-level saga orchestration.
* GraphQL gateway.
* Swagger aggregation.
* JWT blacklist check tại Gateway.
* mTLS service-to-service.
* Service account/client credentials riêng.
* Gateway-level circuit breaker.
* Automatic retry.
* Web Application Firewall.
* Multi-region routing.

## 21. Related contracts

* `../common/auth-contract.md`
* `../common/api-standard.md`
* `../common/error-catalog.md`
* `../common/event-envelope.md`
* `./auth-service.md`
* `./event-service.md`
* `./inventory-service.md`
* `./order-service.md`
* `./payment-service.md`
* `./ticket-service.md`
* `./checkin-service.md`
* `./notification-service.md`
* `./ai-bio-service.md`
* `./csv-ingestion-service.md`
* `../../backend-service-workflow.md`
