# E-ticket-service validation

Status: PASS_WITH_REMAINING_NOTES
Last updated: 2026-06-17

Tài liệu này ghi trạng thái QA riêng cho `e-ticket-service`.

## Scope

`e-ticket-service` chịu trách nhiệm:

- Issue ticket sau khi order/payment thành công.
- Lưu QR token và ticket state.
- Cho customer xem/cancel ticket trong phạm vi owner.
- Cung cấp internal endpoint cho check-in.
- Thực hiện atomic check-in để chống dùng vé hai lần.
- Cung cấp snapshot cho offline check-in.

## Validation matrix

| Tiêu chí | Trạng thái | Ghi chú |
|---|---|---|
| API envelope | PASS | Trả `ApiResponse<T>` |
| Public/internal boundary | PASS | Public `/api/tickets/**`, internal `/internal/tickets/**` |
| Auth source | PASS | User id lấy từ `SecurityContext` |
| JWT contract | PASS | Verify signature, `exp`, `iss=tickefy-auth-service`, `aud=tickefy-api`; service security tests cover wrong issuer/audience -> `INVALID_TOKEN` |
| Idempotent issue | PASS | Unique `orderItemId` và fallback reload |
| Atomic check-in | PASS | Update theo điều kiện `status = ISSUED` |
| Ticket DTO | PASS | Public list/detail trả `ticketTypeName` và `qrTokenMasked`, không trả raw `qrToken` |
| QR endpoint | PASS | Owner/admin-only `GET /api/tickets/{ticketId}/qr` là nơi duy nhất trả raw `qrToken` để render QR |
| Snapshot | PASS | Trả `qrTokenMasked` và `qrTokenHash`, không trả raw `qrToken` |
| TicketsIssued event | PASS | Publish batch envelope `TicketsIssued` qua routing key `tickets.issued`, không chứa QR |
| Request log masking | PASS | Path `/internal/tickets/by-token/{token}` được log thành `{qrTokenMasked}` |
| Security tests | PASS | Đã cover role và spoof header |
| Docker build | PASS | Image build thành công |

## Test evidence

Các test document chi tiết:

- Unit/Integration: `01-unit-integration.md` (trong thư mục này)
- Testcontainers DB: `02-real-db-testcontainers.md`
- API Contract (REST Assured): `03-api-rest-assured.md`
- Security: `04-security.md`

Evidence mới nhất cho pass stabilization ngày 2026-06-17:

- Maven test: `services/e-ticket-service/mvnw.cmd test` -> PASS, `28` tests, `0` failures, `0` errors.
- Real API smoke: tạo user thật, tạo order từ seeded inventory, simulate paid, e-ticket consume `OrderPaid`, issue đúng `2` tickets cho quantity `2`.
- API response check: ticket list/detail có `ticketTypeName` + `qrTokenMasked`; không có `ticketName` hoặc raw `qrToken`.
- QR response check: owner QR endpoint trả raw `qrToken`; normal list/detail/internal snapshot không trả raw QR.
- Snapshot check: response có `qrTokenMasked` + `qrTokenHash`.
- Event check: unit tests cover `TicketsIssued` envelope, routing key `tickets.issued`, stable child `messageId`, no QR fields, replay idempotency.
- Logging check: request path chứa `/internal/tickets/by-token/{token}` không ghi raw token trong log.

Chi tiết cross-service smoke: dùng các test doc trong thư mục này thay thế.

## Resolved in 2026-06-17 stabilization pass

| Id | Kết quả |
|---|---|
| BE-RAISE-003 | Đã publish batch event `TicketsIssued` theo contract MVP |

## Raised issues còn lại

| Id | Mức độ | Vấn đề | Đề xuất |
|---|---|---|---|
| BE-RAISE-001 | P2 | QR token vẫn nằm trong path của `/internal/tickets/by-token/{token}` dù log đã mask | Đổi sang `POST` body-based lookup khi team chốt breaking change |
| BE-RAISE-002 | P2 | Snapshot hiện là full snapshot cho ticket syncable; chưa có delta/incremental invalidation | Thiết kế delta/incremental snapshot nếu offline mode cần cập nhật hủy/hoàn tiền theo thời gian thực hơn |
