---
title: Service Specification - api-gateway
status: IMPLEMENTED
version: 1.2
owner: Hoàng
reviewers: [BE Lead, Auth Service, Frontend, Mobile]
lastUpdated: 2026-06-19
---

# Service Specification — `api-gateway`

`api-gateway` là entry point HTTP cho client khi chạy tích hợp. Gateway không sở hữu business data; nhiệm vụ chính là CORS, request ID, JWT verification tại edge, rate limit, route và proxy request sang service phù hợp.

## Identity

| Item | Value |
| --- | --- |
| Source | `services/api-gateway` |
| Technology | Spring Boot WebFlux + Spring Cloud Gateway |
| Container port | `8080` |
| Public base path | `/api/**` |
| Health | `/actuator/health`, `/livez`, `/readyz` |
| Database | None |
| RabbitMQ | None |
| Redis | Rate limiting only |

## Responsibilities

Gateway chịu trách nhiệm:

- Route request tới downstream service bằng configured URL.
- Rewrite `/api` cho route Auth và Order khi controller downstream không có prefix `/api`.
- Verify JWT RS256 cho protected routes.
- Forward nguyên `Authorization`, `Cookie`, `Set-Cookie`, `Idempotency-Key`, `X-Request-ID`.
- Tạo hoặc propagate `X-Request-ID`.
- Xóa client-supplied `X-User-ID` và `X-User-Roles`.
- Cấu hình CORS, rate limiting, timeout, upload transport limit, access log và metrics.
- Trả common error envelope cho lỗi Gateway tạo ra.

Gateway không chịu trách nhiệm:

- Không xử lý business logic, không query DB, không publish/consume RabbitMQ event.
- Không thay downstream authorization, role check, ownership check hoặc idempotency.
- Không retry command gây side effect.
- Không expose `/internal/**`, `/dev/**`, actuator/swagger của downstream.
- Không wrap lại response thành công hoặc business error từ downstream.

## Dependencies

Downstream URL lấy từ environment. Trong Docker Compose, dùng service name và container port `8080`.

| Service | Default Docker URL | Notes |
| --- | --- | --- |
| `auth-service` | `http://auth-service:8080` | Register, login, refresh, logout, user APIs |
| `event-service` | `http://event-service:8080` | Concert APIs |
| `inventory-service` | `http://inventory-service:8080` | Availability/ticket type reads |
| `order-service` | `http://order-service:8080` | Create/read orders |
| `payment-service` | `http://payment-service:8080` | Payment APIs/callback |
| `ticket-service` | `http://ticket-service:8080` | Ticket APIs |
| `checkin-service` | `http://checkin-service:8080` | Check-in APIs |
| `notification-service` | `http://notification-service:8080` | Notification APIs/SSE |
| `ai-bio-service` | `http://ai-bio-service:8080` | AI bio jobs/uploads |
| `csv-ingestion-service` | `http://csv-ingestion-service:8080` | CSV import |

Local compose hiện có thể override `TICKET_SERVICE_URL=http://e-ticket-service:8080` nếu service thật là `e-ticket-service`.

## Route Contract

| Route ID | External path | Downstream | Rewrite / metadata |
| --- | --- | --- | --- |
| `auth-service-route` | `/api/auth/**` | `auth-service` | `StripPrefix=1`: `/api/auth/** -> /auth/**` |
| `event-service-public-route` | `/api/concerts/**` | `event-service` | No rewrite |
| `event-service-admin-route` | `/api/admin/concerts/**` | `event-service` | No rewrite |
| `inventory-service-route` | `/api/inventory/**` | `inventory-service` | No rewrite |
| `order-service-route` | `/api/orders/**` | `order-service` | `StripPrefix=1`: `/api/orders/** -> /orders/**` |
| `order-service-my-orders-route` | `/api/users/me/orders` | `order-service` | `StripPrefix=1`: `/api/users/me/orders -> /users/me/orders` |
| `payment-service-route` | `/api/payments/**` | `payment-service` | No rewrite |
| `payment-service-admin-route` | `/api/admin/payments/**` | `payment-service` | No rewrite |
| `ticket-service-route` | `/api/tickets/**` | ticket/e-ticket service | No rewrite |
| `checkin-service-route` | `/api/checkin/**` | `checkin-service` | 5s response timeout |
| `notification-service-sse-route` | `GET /api/notifications/stream` | `notification-service` | Response timeout disabled |
| `notification-service-route` | `/api/notifications/**` | `notification-service` | 15s response timeout |
| `ai-bio-upload-route` | `POST /api/ai-bio/concerts/*/jobs` | `ai-bio-service` | 60s response timeout, multipart upload limit 30MB, no path rewrite |
| `ai-bio-service-route` | `/api/ai-bio/**` | `ai-bio-service` | 15s response timeout, no path rewrite |
| `csv-ingestion-upload-route` | `POST /api/admin/csv-import` | `csv-ingestion-service` | 60s response timeout, upload limit |
| `csv-ingestion-service-route` | `/api/admin/csv-import/**` | `csv-ingestion-service` | 15s response timeout |

## Authentication

Public endpoints:

| Method | Path |
| --- | --- |
| `OPTIONS` | `/**` |
| `GET` | `/actuator/health`, `/actuator/health/**`, `/livez`, `/readyz` |
| `POST` | `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh-token` |
| `GET` | `/api/concerts`, `/api/concerts/**` |
| `GET` | `/api/inventory/**` |
| `POST` | `/api/payments/callback` |

Mọi endpoint khác yêu cầu `Authorization: Bearer <access-token>`.

JWT verification:

- Algorithm: `RS256`
- Required valid signature and `exp`
- Issuer: `JWT_ISSUER`, default `tickefy-auth-service`
- Audience: `JWT_AUDIENCE`, default `tickefy-api`
- Public key: `JWT_PUBLIC_KEY_PATH`, Docker compose mounts it at `/keys/public.pem`

Downstream service vẫn phải verify JWT lần hai và tự kiểm tra role/ownership/business permission.

## Header And CORS

Gateway propagates:

```text
Authorization
Content-Type
Accept
Cookie
Set-Cookie
Idempotency-Key
X-Request-ID
Retry-After
Location
```

CORS:

- Allowed origins: `CORS_ALLOWED_ORIGINS`
- Credentials: `true`
- Methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`
- Allowed headers: `Authorization, Content-Type, Accept, X-Request-ID, Idempotency-Key`
- Exposed headers: `X-Request-ID, Retry-After, Location`
- Wildcard origin is rejected when credentials are enabled.

## Rate Limiting

Gateway dùng Redis-backed token bucket và fail-open nếu Redis/rate limiter lỗi.

| Policy | Match | Default |
| --- | --- | --- |
| `auth` | `POST /api/auth/login`, `/register`, `/refresh-token` | 5 rps, burst 10 |
| `purchase` | `POST /api/orders` | 2 rps, burst 4 |
| `default` | Các request còn lại | 20 rps, burst 40 |

Rate-limit key:

- Authenticated request: JWT `sub`
- Anonymous/unparseable token: client IP
- Trusted forwarded headers chỉ dùng khi `RATE_LIMIT_TRUST_FORWARDED_HEADERS=true`

Limit exceeded returns `429 RATE_LIMIT_EXCEEDED` with `Retry-After`.

## Timeout, Upload, Error

Timeout defaults:

| Route type | Connect | Response |
| --- | ---: | ---: |
| Default REST | 2000ms | 15s |
| Check-in | 2000ms | 5000ms |
| Upload | 2000ms | 60000ms |
| SSE | 2000ms | disabled |

Upload transport checks use `Content-Length` only:

| Upload | Limit | Gateway error |
| --- | ---: | --- |
| AI Bio multi-source job | 30MB | `413 SOURCE_TOO_LARGE` |
| CSV import | 12MB | `413 FILE_TOO_LARGE` |

Gateway-generated errors use:

```json
{
  "success": false,
  "data": null,
  "error": {
    "httpStatus": 401,
    "code": "INVALID_TOKEN",
    "message": "Invalid token.",
    "details": {}
  },
  "requestId": "req-uuid",
  "timestamp": "2026-06-18T00:00:00Z"
}
```

Common Gateway error codes:

| HTTP | Code |
| ---: | --- |
| 401 | `UNAUTHORIZED`, `INVALID_TOKEN` |
| 403 | `FORBIDDEN` |
| 404 | `RESOURCE_NOT_FOUND` |
| 413 | `SOURCE_TOO_LARGE`, `FILE_TOO_LARGE` |
| 429 | `RATE_LIMIT_EXCEEDED` |
| 503 | `SERVICE_UNAVAILABLE` |
| 500 | `INTERNAL_SERVER_ERROR` |

Downstream status/body are preserved for routed responses.
