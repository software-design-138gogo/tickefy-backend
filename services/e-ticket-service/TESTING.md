# Hướng dẫn kiểm thử e-ticket-service

Status: ACTIVE
Last updated: 2026-06-13

Tài liệu này mô tả các test case quan trọng của `e-ticket-service`. Service này là source of truth cho ticket state, QR token, ownership và atomic check-in.

## 1. Mục tiêu kiểm thử

`e-ticket-service` phải bảo vệ hai nguyên tắc chính:

1. **Idempotency:** Cùng một `orderItemId` chỉ được tạo đúng một ticket, kể cả khi request bị retry do timeout hoặc network lag.
2. **Double-spending prevention:** Cùng một QR ticket chỉ được check-in thành công một lần. Các request cùng lúc còn lại phải bị reject bằng mã ổn định như `DUPLICATE_REJECTED`.

Ngoài ra, service phải đảm bảo customer chỉ đọc được vé của chính họ và internal endpoint không bị public.

## 2. Các loại kiểm thử (Test Types)

Hiện service này có **4 loại test chính**. Mỗi loại trả lời một câu hỏi khác nhau:

| Loại test | Câu hỏi cần trả lời | Cách test | File liên quan |
|---|---|---|---|
| **Service/Business Test** | Logic nghiệp vụ có đúng không? | Gọi trực tiếp method của `TicketService`, kiểm tra response và trạng thái DB | `TicketServiceTest` |
| **Integration/Persistence Test** | Query, transaction, unique constraint, atomic update có hoạt động đúng không? | Chạy với H2 in-memory database, dùng repository thật và transaction thật | `TicketServiceTest` |
| **Concurrency Test** | Có bị race condition khi nhiều request cùng lúc không? | Dùng `ExecutorService` + `CountDownLatch` để thả nhiều thread cùng lúc vào `issueTicket` hoặc `checkIn` | `TicketServiceTest` |
| **Security/Controller Test** | API có chặn đúng JWT/role/owner scope không? | Dùng `MockMvc` gửi HTTP request giả lập với token hợp lệ, token sai role, không token, và spoof header | `TicketControllerSecurityTest` |

### 2.1. Service/Business Test được test ra sao

- Test gọi thẳng method service, ví dụ `issueTicket`, `checkIn`, `getTicketById`.
- Test assert cả output lẫn dữ liệu lưu trong DB.
- Dùng để chứng minh rule nghiệp vụ như idempotency, owner scope, state transition.

### 2.2. Integration/Persistence Test được test ra sao

- Test chạy với H2 in-memory database để không phụ thuộc PostgreSQL local.
- Repository thật được dùng để kiểm tra query và constraint.
- Các case quan trọng: unique `orderItemId`, lookup QR token, atomic update theo `status = ISSUED`.

### 2.3. Concurrency Test được test ra sao

- Test tạo nhiều thread bằng `ExecutorService`.
- `CountDownLatch` giữ các thread lại, sau đó thả cùng lúc để tạo race thật.
- Kỳ vọng chỉ một thread thắng, các thread còn lại nhận kết quả reject/idempotent.

### 2.4. Security/Controller Test được test ra sao

- Test không gọi service trực tiếp mà đi qua HTTP layer bằng `MockMvc`.
- JWT được giả lập với nhiều role khác nhau.
- Test kiểm tra endpoint public/internal, owner scope, và chống giả mạo `X-User-Id`.

## 3. Test suite chính

| Test class | Mục tiêu |
|---|---|
| `TicketServiceTest` | Business logic, persistence, idempotency, concurrency, state classification |
| `TicketControllerSecurityTest` | JWT, role check, owner scope, chống spoof header |

## 4. Test cases quan trọng

### 3.1. Idempotent issue ticket

- **Tình huống:** Order/payment flow gọi cấp vé nhiều lần với cùng `orderItemId`.
- **Cơ chế:** Gọi `ticketService.issueTicket(req)` nhiều lần.
- **Kỳ vọng:** Service trả về cùng một ticket, không tạo duplicate record trong bảng `tickets`.

### 3.2. Concurrent issue ticket

- **Tình huống:** Nhiều request cùng cấp vé cho một `orderItemId` tại cùng thời điểm.
- **Cơ chế:** Dùng `ExecutorService` và `CountDownLatch` để tạo race.
- **Kỳ vọng:** Chỉ một record được insert. Request thua unique constraint sẽ reload ticket đã tồn tại.

### 3.3. Atomic check-in race condition

- **Tình huống:** Nhiều thiết bị scan cùng một QR ticket cùng lúc.
- **Cơ chế:** Repository dùng atomic update với điều kiện `status = ISSUED`.

```sql
UPDATE tickets
SET status = 'CHECKED_IN', checked_in_at = now()
WHERE id = :id AND status = 'ISSUED'
```

- **Kỳ vọng:** Chỉ một request trả `ACCEPTED`. Các request còn lại trả `DUPLICATE_REJECTED` hoặc mã reject tương ứng với trạng thái hiện tại.

### 3.4. QR token validation

- **Tình huống:** Internal check-in gọi lookup một QR token không tồn tại.
- **Cơ chế:** Gọi lookup by token.
- **Kỳ vọng:** Service trả lỗi `INVALID_QR_TOKEN`.

### 3.5. Customer ownership

- **Tình huống:** Customer A cố đọc/cancel vé của Customer B.
- **Cơ chế:** Controller lấy user id từ `SecurityContext`, không lấy từ `X-User-Id`.
- **Kỳ vọng:** Non-owner bị reject. Spoof header không đổi được owner scope.

### 3.6. Internal endpoint authorization

- **Tình huống:** Customer token gọi `/internal/tickets/**`.
- **Kỳ vọng:** Bị reject vì thiếu role `CHECKIN_STAFF`, `STAFF`, `ADMIN`, `ORGANIZER` tùy endpoint.

## 5. Chạy kiểm thử

Chạy toàn bộ test của service:

```powershell
.\mvnw.cmd clean test
```

Chạy service/business/integration/concurrency tests:

```powershell
.\mvnw.cmd -Dtest=TicketServiceTest test
```

Chạy security/controller tests:

```powershell
.\mvnw.cmd -Dtest=TicketControllerSecurityTest test
```

Ghi evidence từ thư mục `tickefy-backend/services/e-ticket-service`:

```powershell
.\mvnw.cmd clean test *> ..\..\evidence\e-ticket-service\mvn-test.log
.\mvnw.cmd -Dtest=TicketServiceTest test *> ..\..\evidence\e-ticket-service\concurrency-test.log
.\mvnw.cmd -Dtest=TicketControllerSecurityTest test *> ..\..\evidence\e-ticket-service\security-test.log
```

## 6. Checklist trước khi sửa service

- Không bỏ unique constraint/idempotency theo `orderItemId`.
- Không đổi atomic update thành read-then-write thường.
- Không lấy user id từ request body/header.
- Không public internal endpoint.
- Không sửa applied Flyway migration; tạo migration mới nếu cần.
