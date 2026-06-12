# Checkin Service Testing Guide

Tài liệu này mô tả chi tiết các kịch bản kiểm thử (Test Cases) đã được cài đặt trong `checkin-service`, tập trung vào luồng soát vé Online và đặc biệt là giải quyết xung đột khi soát vé Offline (Offline Conflict Resolution).

---

## 1. Mục tiêu kiểm thử

Check-in Service phải giải quyết bài toán môi trường mạng không ổn định ở các sân vận động lớn:
1. **Online Verification:** Xác thực vé trực tiếp qua E-Ticket service.
2. **Offline Conflict Rule (First-Server-Wins):** Nếu mạng chập chờn, nhiều thiết bị cùng quẹt 1 vé ở chế độ Offline. Khi có mạng lại, hệ thống phải phát hiện xung đột khi đồng bộ, và áp dụng quy tắc "Vé nào về Server trước thì hợp lệ, vé nào về Server sau thì là quét trùng".
3. **Idempotency của Batch Sync:** Do mạng yếu, gói đồng bộ có thể bị gửi lặp lại nhiều lần. Server phải không bao giờ ghi đúp log.

---

## 2. Kịch bản kiểm thử đã triển khai (`CheckinServiceTest.java`)

### 2.1. Kiểm thử Đồng bộ Offline chống trùng lặp (Sync Idempotency)
- **Tình huống giả lập:** Thiết bị di động của nhân viên soát vé báo mất kết nối, người dùng bấm "Đồng bộ" liên tục tạo ra 2 request Sync y hệt nhau.
- **Cơ chế Test:** Mock `ETicketClient` để trả về vé hợp lệ. Tạo `SyncRequest` và gọi API `sync(req)` 2 lần.
- **Kết quả kỳ vọng (Assert):** Lần gọi thứ 2 bỏ qua phần xử lý logic, không gọi sang E-Ticket Service (mock client `never()` invoked), và trả về đúng nguyên vẹn payload của lần 1. Bảng `sync_batches` chỉ có 1 record.

### 2.2. Kiểm thử Xung đột Offline (First-Wins Conflict Resolution)
- **Tình huống giả lập:** Thiết bị 1 và Thiết bị 2 cùng lúc mất mạng, và vô tình cùng quẹt chung một mã QR được in sao chép. Cả 2 máy đều báo màn hình Vàng (Tạm chấp nhận Offline). Khi có mạng, Thiết bị 1 gửi Sync lên trước, Thiết bị 2 gửi Sync lên sau.
- **Cơ chế Test:** 
  - Mock `ETicketClient` ở lần gọi thứ nhất trả về trạng thái vé là `ISSUED`, ở lần gọi thứ 2 trả về trạng thái vé là `CHECKED_IN` (Mô phỏng E-Ticket đã ghi nhận vé này được dùng).
  - Khởi tạo `SyncRequest` cho Thiết bị 1.
  - Khởi tạo `SyncRequest` cho Thiết bị 2.
  - Gọi API sync cho Thiết 1 rồi đến Thiết bị 2.
- **Kết quả kỳ vọng (Assert):**
  - Payload trả về cho Thiết bị 1: `accepted` = 1, `conflicts` = 0. (Hợp lệ)
  - Payload trả về cho Thiết bị 2: `accepted` = 0, `conflicts` = 1, với lý do `DUPLICATE_REJECTED`. (Báo động có vé trùng lặp).

### 2.3. Kiểm thử Online Scan
- **Tình huống giả lập:** Nhân sự bấm quét vé trong lúc mạng đang hoạt động tốt.
- **Cơ chế Test:** Bắn API `scan(req)` cho vé hợp lệ và vé đã sử dụng.
- **Kết quả kỳ vọng (Assert):**
  - Vé hợp lệ: `ScanResponse` trả về `ACCEPTED`. Log trong `checkin_events` cũng ghi nhận `ACCEPTED`.
  - Vé đã dùng: `ScanResponse` trả về `DUPLICATE_REJECTED`. Log trong `checkin_events` cũng ghi nhận `DUPLICATE_REJECTED`.

---

## 3. Chạy kiểm thử

Chạy toàn bộ test suites bằng lệnh Maven (kết nối với H2 in-memory DB và Mockito):

```powershell
.\mvnw.cmd test -Dtest=CheckinServiceTest
```
