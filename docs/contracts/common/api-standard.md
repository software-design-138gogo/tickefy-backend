---
title: API Standard
status: ACCEPTED
version: 1.0
owner: BE Lead (Hiệp)
reviewers: [Dương, Hòa, Hoàng]
lastUpdated: 2026-06-17
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
- Protected request đi qua API Gateway phải được Gateway verify JWT để reject sớm, nhưng Gateway không phải lớp bảo mật duy nhất. Protected service vẫn phải tự verify RS256 bằng public key và tự kiểm role/ownership nghiệp vụ.
- Gateway phải forward nguyên `Authorization: Bearer <access-token>` cho downstream service. Service không được xem `X-User-*` header là nguồn xác thực/phân quyền duy nhất.
- Client có thể gửi `X-Request-ID`; backend echo lại ở response header + body; thiếu thì backend tự sinh.
- **Idempotency key:** contract mới dùng HTTP header `Idempotency-Key` (ví dụ `ai-bio-service`). Một số endpoint cũ như create-order vẫn giữ field body `idempotencyKey` cho tương thích; service contract cụ thể là SSOT cho vị trí key. Backend lưu key theo scope UNIQUE và replay cùng request phải trả kết quả cũ (xem §10).

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
    "code": "CONFLICT",
    "message": "Trạng thái tài nguyên xung đột.",
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
| 200 | Thành công / idempotent replay / business result đã xử lý được | `data.replayDetected=true`, `data.result=\"DUPLICATE_REJECTED\"` |
| 201 | Tạo mới | order, phát hành vé |
| 202 | Nhận job async | CSV import accepted (🔭 chưa implement — csv-ingestion skeleton) |
| 400 | Validation / request sai format | `VALIDATION_ERROR`, `INVALID_QR_TOKEN` khi QR malformed |
| 401 | Chưa xác thực / token sai | `UNAUTHORIZED`, `INVALID_TOKEN`, `TOKEN_REVOKED`, `INVALID_CREDENTIALS` |
| 403 | Đã xác thực, không đủ quyền | `FORBIDDEN`, `SALE_WINDOW_CLOSED` |
| 404 | Không tìm thấy | `*_NOT_FOUND` |
| 409 | Conflict hệ thống hoặc command không thể hoàn tất vì state hiện tại | `TICKET_SOLD_OUT`, `LAST_ADMIN`, `CONFLICT` |
| 410 | Hết hạn | `RESERVATION_EXPIRED`, `SNAPSHOT_EXPIRED` |
| 422 | Đúng format nhưng transition không hợp lệ | `INVALID_STATE_TRANSITION`, `PER_USER_LIMIT_EXCEEDED` |
| 429 | Rate limit | `RATE_LIMIT_EXCEEDED` |
| 500 | Lỗi server chưa xử lý | `INTERNAL_SERVER_ERROR` |
| 503 | Dependency tạm unavailable | `SERVICE_UNAVAILABLE` |

> ⚠️ **KHÔNG dùng `TOKEN_EXPIRED`.** Backend auth-service trả `INVALID_TOKEN` (hoặc `TOKEN_REVOKED`) cho token hết hạn/bị thu hồi — KHÔNG có mã `TOKEN_EXPIRED`. Client refresh trên **BẤT KỲ 401** (thử 1 lần, loại trừ endpoint refresh), không key vào một mã cụ thể.
> Không trả `200 OK` cho lỗi hệ thống/validation/auth.
>
> ✅ **Check-in business rejection là kết quả nghiệp vụ, không phải API error:** duplicate scan, wrong concert, cancelled/refunded ticket trả `HTTP 200` + `success=true` + `data.result` theo `./checkin-result-catalog.md`. Chỉ dùng error envelope cho auth/permission/validation/system/dependency failures.

## 8. Business result vs API error

Một endpoint có thể xử lý request hợp lệ và trả về kết quả nghiệp vụ không thành công. Trường hợp này **không dùng** `success=false` nếu hệ thống đã xử lý request đúng cách.

Áp dụng bắt buộc cho check-in online/offline:

| Case | HTTP | Envelope | Client branch |
|---|---:|---|---|
| Vé hợp lệ | 200 | `success=true`, `data.result=\"ACCEPTED\"` | Cho khách vào |
| Vé đã check-in | 200 | `success=true`, `data.result=\"DUPLICATE_REJECTED\"` | Từ chối, cảnh báo duplicate |
| Vé sai concert | 200 | `success=true`, `data.result=\"WRONG_EVENT\"` | Từ chối, hiển thị sai sự kiện |
| Vé cancelled/refunded | 200 | `success=true`, `data.result=\"CANCELLED_REJECTED\"` / `\"REFUNDED_REJECTED\"` | Từ chối |
| QR malformed, body sai schema | 400 | `success=false`, `error.code=\"VALIDATION_ERROR\"` hoặc `\"INVALID_QR_TOKEN\"` | Báo lỗi request |
| Staff thiếu quyền | 403 | `success=false`, `error.code=\"FORBIDDEN\"` | Forbidden/login lại |
| Ticket Service down | 503 | `success=false`, `error.code=\"TICKET_SERVICE_UNAVAILABLE\"` | Retry/backoff |

Canonical check-in result response:

```json
{
  "success": true,
  "data": {
    "result": "DUPLICATE_REJECTED",
    "ticketId": "ticket-uuid",
    "concertId": "concert-uuid",
    "checkedInAt": null,
    "replayDetected": false,
    "message": "Vé đã được check-in trước đó."
  },
  "error": null,
  "requestId": "req-uuid",
  "timestamp": "2026-06-16T10:00:00Z"
}
```

Client/mobile phải branch bằng `data.result` cho check-in business result và bằng `error.code` cho API error.

## 9. Validation error details
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

## 10. Pagination
Request: `GET /api/events?page=0&size=20&sort=eventDate,asc`
Response `data`:
```json
{ "items": [], "page": 0, "size": 20, "total": 150, "totalPages": 8 }
```
> ⚠️ **Code cần đồng bộ:** controller hiện trả Spring `Page<T>` trực tiếp (`UserController.listUsers`, `OrderController.getMyOrders`) → JSON serialize thành `{content, totalElements, totalPages, number, size, ...}`, KHÁC shape `{items, total, ...}` ở trên. Doc giữ shape `{items,...}` là target — backend cần wrap `Page`→`PagedResponse` (Spring `PageImpl` JSON còn unstable). (Sửa code ngoài phạm vi task doc này.)

## 11. Idempotency & replay
- Command nhạy gửi key idempotent theo service contract: ưu tiên header `Idempotency-Key`; endpoint cũ có thể giữ body `idempotencyKey` để tương thích. Backend lưu key UNIQUE theo scope.
- 🔭 **PLANNED (response shape chưa code):** gửi lại cùng key sau khi thao tác trước đã thành công → **không phải lỗi**: trả lại kết quả cũ, **HTTP 200** + `data.replayDetected=true`. Hiện code resume trả order cũ nhưng CHƯA gắn cờ `replayDetected`/chuẩn-hoá 200. Giữ là target.
```json
{ "success": true, "data": { "orderId": "...", "status": "RESERVED", "replayDetected": true },
  "error": null, "requestId": "req-uuid", "timestamp": "2026-06-16T10:00:00Z" }
```

## 12. Request tracing
- Nhận hoặc tự sinh `X-Request-ID` → echo response header + body → gắn MDC/log.
- **Propagate** sang HTTP downstream và message broker.
- Lưu ý: `requestId` (tracing) khác `messageId` (dedup event). Event có `messageId` riêng trong envelope — xem `./event-envelope.md`.

## 13. Quy tắc client (frontend / mobile / admin)
1. Branch logic bằng `error.code`, KHÔNG `error.message`.
2. Branch check-in business result bằng `data.result`, KHÔNG parse `data.message`.
3. Hiển thị `requestId` cho lỗi 5xx (để báo lỗi/trace).
4. Map `VALIDATION_ERROR.details` vào form fields.
5. `data === null` là invariant khi `success === false`.
6. Ưu tiên UX message phía client (vd `resolveErrorMessage(code)` / `resolveCheckinResultMessage(result)`) thay vì raw backend message. Mã lạ ngoài catalog → message chung, KHÔNG vỡ.

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

## 14. Quy tắc backend implementation
- Trả response qua helper `ApiResponse<T>` chung.
- Map domain exception ở `GlobalExceptionHandler` (không try/catch rải rác).
- Controller **mỏng**: validate input (`@Valid`), gọi service/use-case, trả DTO. Business decision ở service layer.
- KHÔNG expose entity trực tiếp; KHÔNG trả stack trace / exception class name.
- Error code giữ trong catalog/enum ổn định; gắn `requestId` vào log MDC.
- Check-in expected rejection phải trả `success=true` + `data.result` nếu request hợp lệ và staff được phép scan.
- Enum state máy đúng contract (vd Payment state = `SUCCESS`, KHÔNG `SUCCEEDED`; Order state machine theo `../services/order-service.md`).

## 15. Logging safety
- **Được log:** `requestId, userId, staffId, concertId, ticketId, orderId, deviceId, gate, result code`.
- **Phải mask:** `qrToken, JWT, password, secret, payment signature, full card/bank data`. QR token chỉ log prefix/masked value (`qrTokenMasked=abc12345...`), không full trong log thường.

## 16. Tài liệu liên quan
- `../services/` — contract endpoint từng service (SSOT per-service, đang điền).
- `../flows/` — contract luồng cross-service (purchase, payment-ticket, check-in...).
- `./event-envelope.md` — envelope + contract event.
- `./error-catalog.md` — danh mục mã lỗi.
- `./checkin-result-catalog.md` — danh mục result code cho online/offline check-in.
- `./auth-contract.md` — auth / JWT / role.
