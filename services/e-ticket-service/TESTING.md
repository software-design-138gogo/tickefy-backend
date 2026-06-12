# E-Ticket Service Testing Guide

Tài liệu này mô tả chi tiết các kịch bản kiểm thử (Test Cases) đã được cài đặt trong `e-ticket-service`, đặc biệt tập trung vào việc đảm bảo tính vẹn toàn dữ liệu (Data Integrity) dưới tải cao và mạng lag.

---

## 1. Mục tiêu kiểm thử

E-ticket Service là chốt chặn cuối cùng trước khi vé đến tay người dùng. Nó phải đảm bảo hai nguyên tắc bất di bất dịch:
1. **Idempotency (Tính lũy đẳng):** Một giao dịch mua vé thành công dù bị gửi lại nhiều lần (do timeout, lag mạng) cũng chỉ được phép sinh ra đúng 1 vé duy nhất.
2. **Double-spending Prevention (Chống xài vé đúp):** Một mã QR vé, dù bị 100 thiết bị soát vé bắn request vào cùng 1 mili-giây, cũng chỉ được phép `ACCEPTED` 1 lần duy nhất, các request còn lại phải bị từ chối (`DUPLICATE_REJECTED`).

---

## 2. Kịch bản kiểm thử đã triển khai (`TicketServiceTest.java`)

### 2.1. Kiểm thử Idempotent Issue Ticket (Chống cấp vé đúp)
- **Tình huống giả lập:** Order Service gọi API cấp vé 2 lần liên tiếp với cùng một `order_item_id`.
- **Cơ chế Test:** Gọi `ticketService.issueTicket(req)` 2 lần liên tiếp.
- **Kết quả kỳ vọng (Assert):** Lần gọi thứ 2 không tạo thêm record mới trong database, mà trả về chính đối tượng `TicketDto` đã tạo ở lần 1. Tổng số bản ghi trong bảng `tickets` phải là 1.

### 2.2. Kiểm thử Atomic Check-in Race Condition (Đa luồng đồng thời)
- **Tình huống giả lập:** Một đám đông cố tình hack hệ thống bằng cách lấy 1 vé và dùng 10 điện thoại quét đồng thời ở cổng cùng một thời điểm chính xác.
- **Cơ chế Test:** 
  - Sử dụng `ExecutorService` để tạo ra một Thread Pool gồm 10 luồng.
  - Sử dụng `CountDownLatch` để chặn 10 luồng này lại ở vạch xuất phát.
  - Sau đó thả `latch.countDown()` để cả 10 luồng cùng lúc đập vào hàm `ticketService.checkIn(ticketId)`.
- **Cơ chế bảo vệ của mã nguồn:** Repository sử dụng câu lệnh JPQL atomic update:
  `UPDATE Ticket t SET t.status = 'CHECKED_IN' WHERE t.id = :id AND t.status = 'ISSUED'`
- **Kết quả kỳ vọng (Assert):** 
  - Trong list 10 kết quả trả về, **chỉ được phép có đúng 1 kết quả là `ACCEPTED`**, và **9 kết quả còn lại phải là `DUPLICATE_REJECTED`**.
  - Trạng thái vé trong DB phải là `CHECKED_IN`.

### 2.3. Kiểm thử Validation QR Token
- **Tình huống giả lập:** API Checkin gọi tra cứu 1 mã QR tào lao.
- **Cơ chế Test:** Gọi `getByToken("invalid-token")`.
- **Kết quả kỳ vọng (Assert):** Hệ thống ném ra `ApiException` với mã lỗi `INVALID_QR_TOKEN`.

---

## 3. Chạy kiểm thử

Chạy toàn bộ test suites bằng lệnh Maven (kết nối với H2 in-memory DB):

```powershell
.\mvnw.cmd test -Dtest=TicketServiceTest
```
