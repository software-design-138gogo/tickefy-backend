# Checkin-service validation

Status: PASS_WITH_REMAINING_NOTES
Last updated: 2026-06-17

Tài liệu này ghi trạng thái QA riêng cho `checkin-service`.

## Scope

`checkin-service` chịu trách nhiệm:

- Online scan QR ticket.
- Gọi `e-ticket-service` để lookup/check-in ticket.
- Lưu audit row trong `checkin_events`.
- Download snapshot cho offline mode.
- Sync offline batch và ghi conflict.
- Trả history endpoint cho staff/admin.

## Validation matrix

| Tiêu chí | Trạng thái | Ghi chú |
|---|---|---|
| API envelope | PASS | Trả `ApiResponse<T>` |
| Auth source | PASS | Staff id lấy từ `SecurityContext` |
| JWT contract | PASS | Verify signature, `exp`, `iss=tickefy-auth-service`, `aud=tickefy-api`; service security tests cover wrong issuer/audience -> `INVALID_TOKEN`; không tin `X-User-*` |
| Online scan | PASS | Accepted/rejected đều có audit |
| Invalid QR mapping | PASS | Không nhầm với downstream outage |
| E-ticket outage mapping | PASS | Trả `ETICKET_SERVICE_UNAVAILABLE` |
| Result code catalog | PASS | Online dùng `CANCELLED_REJECTED`/`REFUNDED_REJECTED`; sync dùng `SYNC_*` result code |
| Snapshot integration | PASS | Dùng field `tickets` với `qrTokenMasked` + `qrTokenHash`, không raw QR |
| Offline sync idempotency | PASS | Replay trả cached result |
| Conflict handling | PASS | Duplicate offline scan vào `conflicts` |
| QR response/log safety | PASS | Sync response trả `qrTokenMasked`; log không ghi raw QR |
| Security tests | PASS | Đã cover role và spoof header |
| Docker build | PASS | Image build thành công |

## Test evidence

Các test document chi tiết:

- Unit/Integration: `01-unit-integration.md` (trong thư mục này)
- Testcontainers DB: `02-real-db-testcontainers.md`
- API Contract (REST Assured): `03-api-rest-assured.md`
- Security: `04-security.md`
- Performance (k6): `05-performance.md`

Evidence mới nhất cho pass stabilization ngày 2026-06-17:

- Maven test: `services/checkin-service/mvnw.cmd test` -> PASS, `19` tests, `0` failures, `0` errors.
- Real API smoke: dùng staff token có role `CHECKIN_STAFF`, gọi snapshot qua checkin-service và scan bằng token thật từ e-ticket-service.
- Snapshot chain check: checkin-service nhận và trả `qrTokenMasked` + `qrTokenHash`; không trả raw `qrToken`.
- Online scan check: kết quả thực tế theo chuỗi `WRONG_EVENT -> ACCEPTED -> DUPLICATE_REJECTED`.
- Sync check: offline sync trả `SYNC_ACCEPTED` và giữ shape response hiện tại.
- Security check: spoofed `X-User-*` không được dùng làm identity/authorization; service-to-service vẫn forward `Authorization: Bearer <access-token>`.
- Logging check: checkin logs ghi `qrMasked=...`, không ghi raw QR.

Chi tiết cross-service smoke: xem `docs/tests/evidence/` — đã xóa, dùng các test doc trong thư mục này thay thế.

## Resolved in 2026-06-17 stabilization pass

| Id | Kết quả |
|---|---|
| BE-RAISE-004 | Không còn dùng `String.intern()` cho sync lock path trong service code hiện tại |
| BE-RAISE-006 | Forward staff JWT được xác nhận là MVP contract: downstream verify JWT issuer/audience |
| BE-RAISE-007 | Result catalog đã chuẩn hóa theo online và sync result code mới |
| BE-RAISE-008 | Sync response không còn trả raw `qrToken`; dùng `qrTokenMasked` |

## Raised issues còn lại

| Id | Mức độ | Vấn đề | Đề xuất |
|---|---|---|---|
| BE-RAISE-009 | P2 | Logging chưa cấu hình file/centralized | Thêm file appender hoặc centralized logging |
