# Checkin-service validation

Status: PASS_WITH_RAISED_ISSUES
Last updated: 2026-06-13

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
| Online scan | PASS | Accepted/rejected đều có audit |
| Invalid QR mapping | PASS | Không nhầm với downstream outage |
| E-ticket outage mapping | PASS | Trả `ETICKET_SERVICE_UNAVAILABLE` |
| Snapshot integration | PASS | Dùng field `tickets` |
| Offline sync idempotency | PASS | Replay trả cached result |
| Conflict handling | PASS | Duplicate offline scan vào `conflicts` |
| Security tests | PASS | Đã cover role và spoof header |
| Docker build | PASS | Image build thành công |

## Test evidence

Evidence nằm ở:

- `evidence/checkin-service/mvn-test.log`
- `evidence/checkin-service/concurrency-test.log`
- `evidence/checkin-service/security-test.log`
- `evidence/checkin-service/docker-build.log`

Cách chạy lại test nằm trong `services/checkin-service/TESTING.md`.

## Raised issues riêng

| Id | Mức độ | Vấn đề | Đề xuất |
|---|---|---|---|
| BE-RAISE-004 | P2 | Sync lock bằng `String.intern()` chỉ ổn trên một instance | Đổi sang DB-based status (`PROCESSING`, `COMPLETED`) |
| BE-RAISE-006 | P2 | Forward staff JWT sang `e-ticket-service` | Theo Auth Contract MVP: forward access token gốc và downstream verify RS256; service token/mTLS chỉ là hardening sau MVP |
| BE-RAISE-007 | P2 | Result naming chưa đồng nhất | Chuẩn hóa result catalog |
| BE-RAISE-008 | P2 | Sync response còn trả raw `qrToken` | Trả masked token hoặc chỉ `localId` |
| BE-RAISE-009 | P2 | Logging chưa cấu hình file/centralized | Thêm file appender hoặc centralized logging |
