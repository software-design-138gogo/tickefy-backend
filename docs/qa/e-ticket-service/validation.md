# E-ticket-service validation

Status: PASS_WITH_RAISED_ISSUES
Last updated: 2026-06-13

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
| Idempotent issue | PASS | Unique `orderItemId` và fallback reload |
| Atomic check-in | PASS | Update theo điều kiện `status = ISSUED` |
| Snapshot | PASS | Trả danh sách ticket dùng cho offline check-in |
| Security tests | PASS | Đã cover role và spoof header |
| Docker build | PASS | Image build thành công |

## Test evidence

Evidence nằm ở:

- `evidence/e-ticket-service/mvn-test.log`
- `evidence/e-ticket-service/concurrency-test.log`
- `evidence/e-ticket-service/security-test.log`
- `evidence/e-ticket-service/docker-build.log`

Cách chạy lại test nằm trong `services/e-ticket-service/TESTING.md`.

## Raised issues riêng

| Id | Mức độ | Vấn đề | Đề xuất |
|---|---|---|---|
| BE-RAISE-001 | P2 | QR token nằm trong path của `/internal/tickets/by-token/{token}` | Đổi sang `POST` body-based lookup |
| BE-RAISE-002 | P2 | Snapshot chỉ trả ticket `ISSUED` | Thiết kế delta/incremental snapshot |
| BE-RAISE-003 | P2 | Chưa publish `TicketIssuedEvent` | Thêm RabbitMQ publisher khi event contract ổn định |
