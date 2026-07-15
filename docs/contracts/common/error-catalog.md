---
title: Error Catalog
status: ACCEPTED
version: 1.1
owner: BE Lead (Hiệp)
reviewers: [Dương, Hòa, Hoàng]
lastUpdated: 2026-06-19
---

# Error Catalog — Tickefy (SSOT mã lỗi)

> **Nguồn DUY NHẤT** cho mọi `error.code`. Format envelope ở `./api-standard.md`. Mã verify theo code thật (auth/inventory/order) + catalog Hòa.
> ⚠️ Khi nhận làm SSOT: per-service contract (`../services/`) trỏ mã lỗi về đây; `./auth-contract.md` §6 là excerpt tiện đọc (full ở đây).

## 1. Quy tắc
- API code = **string ổn định**; không numeric làm contract chính (internal ref `ERR-...` chỉ dùng trong docs).
- Client branch bằng `error.code`, **KHÔNG** parse `message`. Mã lạ ngoài catalog → message chung, không vỡ.
- Code **không đổi ý nghĩa** sau freeze.
- **Naming cross-cutting (chốt):** `INVALID_TOKEN` chỉ cho auth/JWT; QR token lỗi dùng `INVALID_QR_TOKEN` (KHÔNG `INVALID_TOKEN`); duplicate check-in dùng `DUPLICATE_REJECTED` (KHÔNG `CHECKIN_DUPLICATE`); sai sự kiện dùng `WRONG_EVENT`.
- ⚠️ **KHÔNG có `TOKEN_EXPIRED`** — token hết hạn trả `INVALID_TOKEN`; client refresh trên BẤT KỲ 401.

## 2. Common (mọi service)
| Ref | HTTP | Code | Message mặc định | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-COM-001 | 400 | `VALIDATION_ERROR` | Dữ liệu không hợp lệ. | Body sai/thiếu field (bean validation) | Map `details[field]` → form field |
| ERR-COM-002 | 401 | `UNAUTHORIZED` | Chưa xác thực. | Thiếu/sai token | Redirect login |
| ERR-COM-003 | 403 | `FORBIDDEN` | Không đủ quyền. | Đã auth, thiếu quyền | Forbidden screen |
| ERR-COM-004 | 404 | `RESOURCE_NOT_FOUND` | Không tìm thấy tài nguyên. | Order/ticket type… không tồn tại | Not found / refresh |
| ERR-COM-005 | 409 | `CONFLICT` | Xung đột trạng thái. | State machine guard (vd order transition không hợp lệ) | Refresh trạng thái |
| ERR-COM-006 | 429 | `RATE_LIMIT_EXCEEDED` | Quá nhiều yêu cầu. | 🔭 PLANNED — rate limit chưa code (chưa có trong enum/throw); kèm `Retry-After` khi làm | Backoff + retry |
| ERR-COM-007 | 500 | `INTERNAL_SERVER_ERROR` | Lỗi hệ thống. | Lỗi chưa xử lý (kèm `requestId`) | Hiện `requestId`, cho retry |
| ERR-COM-008 | 503 | `SERVICE_UNAVAILABLE` | Dịch vụ tạm thời không khả dụng. | Dependency nội bộ không phản hồi (vd Order khi Inventory down) | Retry/backoff |

## 3. Service-specific

### `auth-service` (Hiệp)
| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-AUTH-001 | 401 | `INVALID_CREDENTIALS` | Email hoặc mật khẩu không đúng. | Login sai (constant-time, chống user-enumeration) | Báo lỗi chung, KHÔNG lộ email tồn tại |
| ERR-AUTH-002 | 401 | `INVALID_TOKEN` | Phiên không hợp lệ. | Chữ ký/`exp` sai **hoặc token hết hạn** | Refresh (any-401), fail→login |
| ERR-AUTH-003 | 401 | `TOKEN_REVOKED` | Phiên đã kết thúc. | Token bị blacklist (đã logout)/refresh revoke | Refresh, fail→login |
| ERR-AUTH-004 | 409 | `EMAIL_ALREADY_EXISTS` | Email đã được đăng ký. | Register email trùng | Báo ở form email |
| ERR-AUTH-005 | 404 | `USER_NOT_FOUND` | Không tìm thấy người dùng. | Role management user không tồn tại | — |
| ERR-AUTH-006 | 400 | `INVALID_ROLE` | Role không hợp lệ. | Role ngoài enum AUDIENCE/ORGANIZER/CHECKIN_STAFF/ADMIN | — |
| ERR-AUTH-007 | 409 | `LAST_ADMIN` | Không thể gỡ ADMIN cuối cùng. | Gỡ role ADMIN của admin enabled cuối (chống khoá hệ thống) | Chặn thao tác |

### `inventory-service` (Hiệp)
| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-INV-001 | 409 | `TICKET_SOLD_OUT` | Hết vé. | Không còn vé loại này | Disable mua, refresh số vé |
| ERR-INV-002 | 422 | `PER_USER_LIMIT_EXCEEDED` | Vượt giới hạn mua. | Quá hạn mức/người (details: `perUserLimit, alreadyOwned, remaining`) | Hiện hạn mức còn lại |
| ERR-INV-003 | 403 | `SALE_WINDOW_CLOSED` | Chưa tới hoặc hết giờ bán. | Ngoài cửa sổ `saleStart/saleEnd` | Hiện trạng thái bán |
| ERR-INV-004 | 410 | `RESERVATION_EXPIRED` | Phiên giữ vé đã hết hạn. | 🔭 PLANNED — TTL release Pass 2 chưa code (chưa có trong inventory enum, chưa throw) | Quay lại chọn vé |
| ERR-INV-005 | 404 | `RESOURCE_NOT_FOUND` | Không tìm thấy ticket type. | ticketTypeId sai/không có | Not found |
| ERR-INV-006 | 404 | `CONCERT_NOT_FOUND` | Không tìm thấy concert. | Event Service báo concertId sai/không có | Refresh/chọn concert khác |

### `order-service` (Hiệp)
Chủ yếu dùng **common**: `CONFLICT` (state machine guard order: CREATED→RESERVED→PAYMENT_PENDING→PAID/…), `RESOURCE_NOT_FOUND` (order không tồn tại), `SERVICE_UNAVAILABLE` (Inventory down). Idempotent command (key body `idempotencyKey`) replay → **200** + `data.replayDetected=true` (🔭 response-shape chưa code — xem `./api-standard.md` §10).

**Re-surface mã nghiệp vụ của Inventory khi reserve fail (✅ đã code):** Order gọi Inventory reserve sync HTTP; lỗi nghiệp vụ được map lại nguyên mã + HTTP:
| Code | HTTP | Khi xảy ra |
|---|---:|---|
| `TICKET_SOLD_OUT` | 409 | reserve trả hết vé |
| `PER_USER_LIMIT_EXCEEDED` | 422 | reserve trả vượt hạn mức |
| `SALE_WINDOW_CLOSED` | 403 | reserve ngoài cửa sổ bán |

> 🔭 PLANNED (defined nhưng chưa throw): `CONCERT_NOT_FOUND`, `RESERVATION_EXPIRED` có trong `order/ErrorCode.java` nhưng chưa có code path nào ném (Pass 2).
> 🔭 PLANNED: `INVALID_STATE_TRANSITION` (422) — nhắc ở `./api-standard.md` §7 cho luồng order transition/refund chưa code; hiện order dùng `CONFLICT` (409) cho state guard. Bổ sung khi làm Pass 2 (refund/ConcertCancelled).

### `payment-service` (Dương) — 🔭 PLANNED (skeleton, provisional)
| Ref | HTTP | Code | Message | Khi xảy ra |
|---|---:|---|---|---|
| ERR-PAY-001 | 503 | `PAYMENT_GATEWAY_UNAVAILABLE` | Cổng thanh toán đang bảo trì. | Circuit breaker OPEN |
| ERR-PAY-002 | 400 | `INVALID_PAYMENT_SIGNATURE` | Chữ ký thanh toán không hợp lệ. | Webhook signature từ provider sai |
| ERR-PAY-003 | 404 | `PAYMENT_NOT_FOUND` | Không tìm thấy giao dịch thanh toán. | `paymentId`/`txId` không tồn tại |
| ERR-PAY-004 | 409 | `PAYMENT_ALREADY_REFUNDED` | Giao dịch đã được hoàn tiền. | Refund lặp lại không cùng idempotency key |
> Bổ sung khi Dương dựng Payment. Payment state = `SUCCESS` (KHÔNG `SUCCEEDED`).

### `event-service` (Dương) — 🔭 PLANNED (CRUD ở branch `feat/event-service`, publish event TODO)
Dùng `CONCERT_NOT_FOUND` cho concert sai/không tồn tại. Bổ sung khi merge + build Phase 2.

| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-EVT-001 | 404 | `CONCERT_NOT_FOUND` | Không tìm thấy concert. | `concertId` không tồn tại ở public/internal concert APIs | Refresh/chọn concert khác |
| ERR-EVT-002 | 403 | `CONCERT_ACCESS_DENIED` | Không có quyền thao tác với concert này. | Organizer không sở hữu concert | Forbidden |
| ERR-EVT-003 | 503 | `OBJECT_STORAGE_UNAVAILABLE` | Kho lưu trữ tạm thời không khả dụng. | Không cấp được pre-signed URL hoặc không truy cập được media bucket | Retry/backoff |

### `ai-bio-service` (Hoàng)
| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-AIBIO-001 | 400 | `SOURCE_REQUIRED` | Cần ít nhất một nguồn đầu vào hợp lệ. | Không có `files[]` hoặc `sourceUrls[]`. | Chọn file hoặc nhập URL. |
| ERR-AIBIO-002 | 415 | `UNSUPPORTED_SOURCE_TYPE` | Loại nguồn đầu vào chưa được hỗ trợ. | Extension/MIME không nằm trong allowlist hoặc image/URL khi Phase 2 chưa bật. | Chọn loại file được hỗ trợ. |
| ERR-AIBIO-003 | 415 | `INVALID_SOURCE_TYPE` | Loại file không khớp nội dung thực tế. | MIME/magic bytes không khớp extension hoặc file giả mạo. | Chọn file hợp lệ. |
| ERR-AIBIO-004 | 413 | `SOURCE_TOO_LARGE` | Nguồn đầu vào vượt giới hạn dung lượng. | Vượt per-file, total upload hoặc URL download limit. | Giảm kích thước/số lượng nguồn. |
| ERR-AIBIO-005 | 404 | `CONCERT_NOT_FOUND` | Không tìm thấy concert. | Event Service báo concert không tồn tại. | Refresh/chọn concert khác. |
| ERR-AIBIO-006 | 403 | `CONCERT_ACCESS_DENIED` | Không có quyền thao tác với concert này. | Organizer không sở hữu concert. | Forbidden. |
| ERR-AIBIO-007 | 503 | `EVENT_SERVICE_UNAVAILABLE` | Event Service tạm thời không khả dụng. | Không validate được concert/ownership. | Retry/backoff. |
| ERR-AIBIO-008 | 409 | `AI_BIO_JOB_ALREADY_ACTIVE` | Concert đang có job AI Bio đang xử lý. | Đã có job `PENDING` hoặc `PROCESSING`. | Poll job hiện tại. |
| ERR-AIBIO-009 | 409 | `AI_BIO_JOB_NOT_RETRYABLE` | Job này không thể retry. | Retry job `SUCCEEDED`, job không retryable hoặc vượt max retry. | Tạo job mới nếu muốn regenerate. |
| ERR-AIBIO-010 | 422 | `NO_USABLE_SOURCE_CONTENT` | Không tìm thấy nội dung dùng được trong nguồn đầu vào. | Tất cả source rỗng, không đọc được hoặc không extract được text. | Upload nguồn khác. |
| ERR-AIBIO-011 | 422 | `DOCUMENT_PASSWORD_PROTECTED` | Tài liệu được bảo vệ bằng mật khẩu. | PDF/DOCX/PPTX không đọc được do password/protection. | Upload file không khóa. |
| ERR-AIBIO-012 | 503 | `OBJECT_STORAGE_UNAVAILABLE` | Kho lưu trữ tạm thời không khả dụng. | Không upload/read được source object. | Retry/backoff. |
| ERR-AIBIO-013 | 503 | `AI_PROVIDER_TIMEOUT` | AI Provider phản hồi quá lâu. | Provider timeout sau retry policy. | Retry sau. |
| ERR-AIBIO-014 | 429 | `AI_PROVIDER_RATE_LIMITED` | AI Provider đang giới hạn tần suất. | Provider trả 429. | Retry theo backoff. |
| ERR-AIBIO-015 | 503 | `AI_PROVIDER_AUTH_FAILED` | Cấu hình AI Provider không hợp lệ. | API key/provider auth sai. | Ops kiểm tra cấu hình. |
| ERR-AIBIO-016 | 422 | `AI_OUTPUT_INVALID` | Kết quả AI không hợp lệ. | Output empty, quá dài, sai language hoặc vi phạm schema. | Retry/regenerate. |
| ERR-AIBIO-017 | 400 | `URL_NOT_ALLOWED` | URL không được phép sử dụng. | URL dùng scheme không hợp lệ, private IP, localhost, metadata IP hoặc redirect không an toàn. | Dùng URL công khai hợp lệ. |
| ERR-AIBIO-018 | 503 | `URL_FETCH_FAILED` | Không lấy được nội dung từ URL. | URL timeout, DNS lỗi, response quá lớn hoặc content-type không hợp lệ. | Upload file thay thế hoặc thử lại. |

Compatibility note:

- `PDF_FILE_REQUIRED` → thay bằng `SOURCE_REQUIRED`.
- `INVALID_PDF_TYPE` → thay bằng `UNSUPPORTED_SOURCE_TYPE` hoặc `INVALID_SOURCE_TYPE`.
- `PDF_TOO_LARGE` → thay bằng `SOURCE_TOO_LARGE`.
- `PDF_PASSWORD_PROTECTED` → thay bằng `DOCUMENT_PASSWORD_PROTECTED`.
- `NO_USABLE_DOCUMENT_CONTENT` → thay bằng `NO_USABLE_SOURCE_CONTENT`.

### `ticket-service` / `checkin-service` / snapshot / sync (Hòa)

> `ticket-service` là tên canonical trong contract; implementation folder hiện tại có thể là `e-ticket-service` (xem `./naming-convention.md`).
>
> Bảng dưới đây chỉ chứa **API error codes** dùng khi `success=false`. Expected scan rejection như duplicate/wrong concert/cancelled/refunded trả `HTTP 200` + `success=true` + `data.result` theo `./checkin-result-catalog.md`.

#### Ticket API errors

| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-TCK-001 | 404 | `TICKET_NOT_FOUND` | Không tìm thấy vé. | `ticketId` không tồn tại hoặc user không được xem vé đó | Not found / refresh |
| ERR-TCK-002 | 400 | `INVALID_QR_TOKEN` | QR token không hợp lệ. | QR malformed, không decode/parse được hoặc thiếu required data | Báo QR không hợp lệ |
| ERR-TCK-003 | 409 | `INVALID_TICKET_STATE` | Trạng thái vé không hợp lệ. | Transition nội bộ không hợp lệ ngoài luồng check-in business result | Refresh trạng thái |

#### Snapshot API errors

| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-SNP-001 | 404 | `SNAPSHOT_NOT_FOUND` | Không tìm thấy snapshot. | `snapshotId` không tồn tại hoặc đã bị xoá | Tải snapshot mới |
| ERR-SNP-002 | 410 | `SNAPSHOT_EXPIRED` | Snapshot đã hết hạn. | Staff dùng snapshot quá hạn | Tải snapshot mới |
| ERR-SNP-003 | 403 | `SNAPSHOT_FORBIDDEN` | Không có quyền tải snapshot này. | Staff không thuộc concert/gate được phân quyền | Forbidden |

#### Offline sync API errors

| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-SYNC-001 | 400 | `SYNC_BATCH_INVALID` | Batch đồng bộ không hợp lệ. | Thiếu `syncBatchId`, `deviceId`, `concertId` hoặc items sai schema | Sửa payload / báo lỗi app |
| ERR-SYNC-002 | 413 | `SYNC_BATCH_TOO_LARGE` | Batch đồng bộ vượt giới hạn. | Số item vượt giới hạn mỗi request | Chia nhỏ batch |
| ERR-SYNC-003 | 503 | `TICKET_SERVICE_UNAVAILABLE` | Ticket Service tạm thời không khả dụng. | Checkin không gọi được ticket-service | Retry/backoff |
| ERR-SYNC-004 | 409 | `SYNC_BATCH_IN_PROGRESS` | Batch đang được xử lý. | Request replay tới khi batch cũ chưa hoàn tất | Poll/retry sau |

#### Check-in result reference

Business result codes không nằm trong `error.code`; xem `./checkin-result-catalog.md` cho:

- `ACCEPTED`
- `DUPLICATE_REJECTED`
- `WRONG_EVENT`
- `CANCELLED_REJECTED`
- `REFUNDED_REJECTED`
- `INVALID_QR_REJECTED`
- `OFFLINE_ACCEPTED_PENDING_SYNC`
- `SYNC_ACCEPTED`
- `SYNC_CONFLICT`

### `csv-ingestion-service` (Hoàng)

| Ref | HTTP | Code | Message | Khi xảy ra | Client action |
|---|---:|---|---|---|---|
| ERR-CSV-001 | 413 | `FILE_TOO_LARGE` | File vượt quá giới hạn dung lượng. | CSV lớn hơn `CSV_MAX_FILE_SIZE_MB` | Chọn file nhỏ hơn |
| ERR-CSV-002 | 400 | `INVALID_FILE_FORMAT` | File CSV không đúng định dạng. | Sai extension/header/format | Sửa file CSV |
| ERR-CSV-003 | 400 | `INVALID_ENCODING` | File phải dùng UTF-8. | CSV không decode được bằng UTF-8 | Xuất lại file UTF-8 |
| ERR-CSV-004 | 404 | `CONCERT_NOT_FOUND` | Không tìm thấy concert. | Event Service báo concert không tồn tại | Refresh/chọn concert khác |
| ERR-CSV-005 | 503 | `OBJECT_STORAGE_UNAVAILABLE` | Kho lưu trữ tạm thời không khả dụng. | Không upload/read được CSV hoặc error report object | Retry/backoff |
| ERR-CSV-006 | 409 | `IMPORT_JOB_NOT_RETRYABLE` | Job import này không thể retry. | Retry job chưa `FAILED` hoặc vượt retry policy | Poll/tạo import mới |
| ERR-CSV-007 | 404 | `IMPORT_JOB_NOT_FOUND` | Không tìm thấy job import. | `importJobId` không tồn tại hoặc user không được xem | Refresh |

CSV row-level reasons như `DUPLICATE_ROW`, invalid email hoặc missing field nằm trong `import_errors.reason`, không dùng làm API `error.code` trừ khi endpoint trả lỗi request-level.

## 4. Tài liệu liên quan
- `./api-standard.md` — format envelope/error và rule business result vs API error.
- `./checkin-result-catalog.md` — result code nghiệp vụ cho online/offline check-in.
- `../services/` — contract endpoint từng service.
- `./event-envelope.md` — contract event.
- `./auth-contract.md` §6 — excerpt mã auth (full ở đây).
