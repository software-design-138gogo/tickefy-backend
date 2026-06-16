---
title: API Standard
status: DRAFT
version: 1.0
owner: BE Lead
reviewers: []
lastUpdated: YYYY-MM-DD
---

# API Standard

## 1. Mục tiêu

Mô tả chuẩn dùng chung cho toàn bộ REST API của hệ thống.

## 2. Base path

```text
/api/<resource>
```

## 3. Request headers

```http
Authorization: Bearer <access-token>
Content-Type: application/json
X-Request-ID: <optional-client-request-id>
Idempotency-Key: <required-for-idempotent-command>
```

## 4. Success response

```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req-uuid",
  "timestamp": "2026-06-16T10:00:00Z"
}
```

## 5. Error response

```json
{
  "success": false,
  "data": null,
  "error": {
    "httpStatus": 400,
    "code": "VALIDATION_ERROR",
    "message": "Dữ liệu không hợp lệ.",
    "details": {}
  },
  "requestId": "req-uuid",
  "timestamp": "2026-06-16T10:00:00Z"
}
```

## 6. Pagination

### Request

```http
GET /api/resources?page=0&size=20&sort=createdAt,desc
```

### Response data

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total": 0,
  "totalPages": 0
}
```

## 7. HTTP status mapping

| HTTP | Khi sử dụng | Ví dụ code |
|---:|---|---|
| 200 | Thành công | `SUCCESS` |
| 201 | Tạo resource | `RESOURCE_CREATED` |
| 202 | Đã nhận job async | `JOB_ACCEPTED` |
| 400 | Validation/request sai | `VALIDATION_ERROR` |
| 401 | Chưa xác thực | `UNAUTHORIZED` |
| 403 | Không đủ quyền | `FORBIDDEN` |
| 404 | Không tìm thấy | `RESOURCE_NOT_FOUND` |
| 409 | Conflict nghiệp vụ | `DUPLICATE_REJECTED` |
| 410 | Hết hạn | `RESERVATION_EXPIRED` |
| 422 | Transition không hợp lệ | `INVALID_STATE_TRANSITION` |
| 429 | Rate limit | `RATE_LIMIT_EXCEEDED` |
| 500 | Lỗi hệ thống | `INTERNAL_SERVER_ERROR` |
| 503 | Dependency unavailable | `SERVICE_UNAVAILABLE` |

## 8. Validation rules

- Dùng field-level error trong `error.details`.
- Client không parse `message` để quyết định logic.
- Không trả stack trace hoặc exception class.

## 9. Request tracing

- Nhận hoặc tự sinh `X-Request-ID`.
- Echo vào response header và body.
- Gắn vào MDC/log context.
- Propagate sang HTTP downstream và message broker.

## 10. Versioning

- MVP: không có `/v1`.
- Breaking change: tạo `/api/v2/...`.
- Non-breaking change: giữ nguyên version.

## 11. Security

- Không log JWT, password, secret, payment signature.
- Mask QR token và dữ liệu thanh toán.
