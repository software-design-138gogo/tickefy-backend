---
title: API Standard
status: ACCEPTED
version: 1.0
owner: BE Lead (Hiệp)
reviewers: [Dương, Hòa, Hoàng]
lastUpdated: 2026-06-16
---

# API Standard — Tickefy (Backend / Frontend / Mobile / Admin)

> Chuẩn **FORMAT** dùng chung cho mọi REST API: envelope, error, HTTP status, tracing, versioning, bảo mật.
> ⚠️ Đây là chuẩn ĐỊNH DẠNG. **Contract cụ thể** (endpoint từng service ở `../services/`, event ở `./event-envelope.md`); **danh mục mã lỗi** ở `./error-catalog.md`. Tài liệu này KHÔNG liệt kê lại mã lỗi — chỉ định nghĩa cách dùng.

## 1. Mục tiêu
- Mọi service trả cùng một envelope JSON.
- Frontend / admin / mobile xử lý lỗi bằng `error.code` ổn định — KHÔNG parse `message`.
- Tester biết shape response mà không phải đoán.
- Log backend và response cùng `requestId` để trace.

## 2. Base path & versioning
```text
/api/<resource>        # MVP — KHÔNG thêm /v1
/api/v2/<resource>     # chỉ khi có breaking change + team thống nhất
```
Non-breaking change → giữ nguyên version. KHÔNG tự thêm `/api/v1`.

## 3. Request headers
```http
Authorization: Bearer <access-token>      # endpoint protected bắt buộc
Content-Type: application/json
X-Request-ID: <optional-client-request-id>
```
- **Service-to-service auth = `Authorization: Bearer`** (access token forward từ JWT). KHÔNG dùng cookie cho cross-service — cookie (HttpOnly) chỉ là kênh refresh token ở web (xem `./auth-contract.md` §1b + `../services/auth-service.md`).
- Client có thể gửi `X-Request-ID`; backend echo lại ở response header + body; thiếu thì backend tự sinh.
- **Idempotency key — hiện đặt trong BODY:** command tạo tài nguyên nhạy (order) gửi field `idempotencyKey` trong request body (`CreateOrderRequest.idempotencyKey`), backend lưu key UNIQUE → gửi lại cùng key trả kết quả cũ (xem §10 replay). (🔭 target khi refactor: chuyển sang HTTP header `Idempotency-Key` — chưa làm.)

## 4. Success envelope
```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req-uuid",
  "timestamp": "2026-06-16T10:00:00Z"
}
```
- `success = true`; `error = null`.
- `data` = payload; command-style success có thể `{}`.
- `requestId` trùng MDC/log context.
- `timestamp` = UTC ISO-8601 do **server** sinh (bắt buộc theo chuẩn; client tolerant nếu service cũ chưa gửi).
  > ⚠️ **Code cần đồng bộ:** `ApiResponse` hiện CHƯA có field `timestamp` (chỉ `success/data/error/requestId`). Doc giữ là target — backend cần thêm field `timestamp` vào `ApiResponse`. (Sửa code ngoài phạm vi task doc này.)

## 5. Error envelope
```json
{
  "success": false,
  "data": null,
  "error": {
    "httpStatus": 409,
    "code": "DUPLICATE_REJECTED",
    "message": "Vé đã được check-in.",
    "details": {}
  },
  "requestId": "req-uuid",
  "timestamp": "2026-06-16T10:00:00Z"
}
```
- `success = false`; `data = null` (invariant — client coi `data===null` là chắc chắn khi `success===false`).
- `error.httpStatus` **phải trùng** HTTP status thật.
- `error.code` = string ổn định từ catalog (`./error-catalog.md`).
- `error.message` an toàn, dễ đọc — KHÔNG stack trace / secret / token.
- `error.details = {}` nếu không có dữ liệu bổ sung.

## 6. String code, KHÔNG numeric
Contract chính dùng **string code** (`"DUPLICATE_REJECTED"`), KHÔNG numeric (`1007` — buộc tra bảng mới hiểu).
Có thể giữ internal ref trong catalog (`ERR-CHK-002 → DUPLICATE_REJECTED`), nhưng API luôn trả string code.

## 7. HTTP status mapping
| HTTP | Khi dùng | Ví dụ code |
|---:|---|---|
| 200 | Thành công / idempotent replay | `data.replayDetected=true` |
| 201 | Tạo mới | order, phát hành vé |
| 202 | Nhận job async | CSV import accepted (🔭 chưa implement — csv-ingestion skeleton) |
| 400 | Validation / request sai format | `VALIDATION_ERROR` |
| 401 | Chưa xác thực / token sai | `UNAUTHORIZED`, `INVALID_TOKEN`, `TOKEN_REVOKED`, `INVALID_CREDENTIALS` |
| 403 | Đã xác thực, không đủ quyền | `FORBIDDEN`, `SALE_WINDOW_CLOSED` |
| 404 | Không tìm thấy | `*_NOT_FOUND`, `INVALID_QR_TOKEN` |
| 409 | Conflict / trùng / lệch trạng thái | `DUPLICATE_REJECTED`, `TICKET_SOLD_OUT`, `LAST_ADMIN` |
| 410 | Hết hạn | `RESERVATION_EXPIRED`, `SNAPSHOT_EXPIRED` |
| 422 | Đúng format nhưng transition không hợp lệ | `INVALID_STATE_TRANSITION`, `PER_USER_LIMIT_EXCEEDED` |
| 429 | Rate limit | `RATE_LIMIT_EXCEEDED` |
| 500 | Lỗi server chưa xử lý | `INTERNAL_SERVER_ERROR` |
| 503 | Dependency tạm unavailable | `SERVICE_UNAVAILABLE` |

> ⚠️ **KHÔNG dùng `TOKEN_EXPIRED`.** Backend auth-service trả `INVALID_TOKEN` (hoặc `TOKEN_REVOKED`) cho token hết hạn/bị thu hồi — KHÔNG có mã `TOKEN_EXPIRED`. Client refresh trên **BẤT KỲ 401** (thử 1 lần, loại trừ endpoint refresh), không key vào một mã cụ thể.
> Không trả `200 OK` cho lỗi hệ thống/validation/auth.

## 8. Validation error details
```json
{
  "success": false, "data": null,
  "error": {
    "httpStatus": 400, "code": "VALIDATION_ERROR", "message": "Dữ liệu không hợp lệ.",
    "details": { "email": "must be a valid email address", "quantity": "must be greater than 0" }
  },
  "requestId": "req-uuid", "timestamp": "2026-06-16T10:00:00Z"
}
```
- Client map `details[fieldName]` vào form field error.
- KHÔNG parse `message` để đoán field lỗi.

## 9. Pagination
Request: `GET /api/events?page=0&size=20&sort=eventDate,asc`
Response `data`:
```json
{ "items": [], "page": 0, "size": 20, "total": 150, "totalPages": 8 }
```
> ⚠️ **Code cần đồng bộ:** controller hiện trả Spring `Page<T>` trực tiếp (`UserController.listUsers`, `OrderController.getMyOrders`) → JSON serialize thành `{content, totalElements, totalPages, number, size, ...}`, KHÁC shape `{items, total, ...}` ở trên. Doc giữ shape `{items,...}` là target — backend cần wrap `Page`→`PagedResponse` (Spring `PageImpl` JSON còn unstable). (Sửa code ngoài phạm vi task doc này.)

## 10. Idempotency & replay
- Command nhạy (tạo order) gửi key idempotent (hiện ở body `idempotencyKey` — xem §3); backend lưu key UNIQUE. **(✅ mechanism đã code:** `OrderEntity.idempotencyKey` UNIQUE + resume theo status.)
- 🔭 **PLANNED (response shape chưa code):** gửi lại cùng key sau khi thao tác trước đã thành công → **không phải lỗi**: trả lại kết quả cũ, **HTTP 200** + `data.replayDetected=true`. Hiện code resume trả order cũ nhưng CHƯA gắn cờ `replayDetected`/chuẩn-hoá 200. Giữ là target.
```json
{ "success": true, "data": { "orderId": "...", "status": "RESERVED", "replayDetected": true },
  "error": null, "requestId": "req-uuid", "timestamp": "2026-06-16T10:00:00Z" }
```

## 11. Request tracing
- Nhận hoặc tự sinh `X-Request-ID` → echo response header + body → gắn MDC/log.
- **Propagate** sang HTTP downstream và message broker.
- Lưu ý: `requestId` (tracing) khác `messageId` (dedup event). Event có `messageId` riêng trong envelope — xem `./event-envelope.md`.

## 12. Quy tắc client (frontend / mobile / admin)
1. Branch logic bằng `error.code`, KHÔNG `error.message`.
2. Hiển thị `requestId` cho lỗi 5xx (để báo lỗi/trace).
3. Map `VALIDATION_ERROR.details` vào form fields.
4. `data === null` là invariant khi `success === false`.
5. Ưu tiên UX message phía client (vd `resolveErrorMessage(code)`) thay vì raw backend message. Mã lạ ngoài catalog → message chung, KHÔNG vỡ.

Canonical TypeScript (dùng ở `@tickefy/shared`):
```ts
export type ApiError = {
  httpStatus: number;
  code: string;
  message: string;
  details?: Record<string, unknown>;
};
export type ApiResponse<TData> = {
  success: boolean;
  data: TData | null;
  error: ApiError | null;
  requestId: string;
  timestamp?: string;   // optional ở client để tolerant service chưa gửi
};
```

## 13. Quy tắc backend implementation
- Trả response qua helper `ApiResponse<T>` chung.
- Map domain exception ở `GlobalExceptionHandler` (không try/catch rải rác).
- Controller **mỏng**: validate input (`@Valid`), gọi service/use-case, trả DTO. Business decision ở service layer.
- KHÔNG expose entity trực tiếp; KHÔNG trả stack trace / exception class name.
- Error code giữ trong catalog/enum ổn định; gắn `requestId` vào log MDC.
- Enum state máy đúng contract (vd Payment state = `SUCCESS`, KHÔNG `SUCCEEDED`; Order state machine theo `../services/order-service.md`).

## 14. Logging safety
- **Được log:** `requestId, userId, staffId, concertId, ticketId, orderId, deviceId, gate, result code`.
- **Phải mask:** `qrToken, JWT, password, secret, payment signature, full card/bank data`. QR token chỉ log prefix (`qrToken=abc12345...`), không full trong log thường.

## 15. Tài liệu liên quan
- `../services/` — contract endpoint từng service (SSOT per-service, đang điền).
- `../flows/` — contract luồng cross-service (purchase, payment-ticket, check-in...).
- `./event-envelope.md` — envelope + contract event.
- `./error-catalog.md` — danh mục mã lỗi.
- `./auth-contract.md` — auth / JWT / role.
