# Hướng dẫn kiểm thử checkin-service

Status: ACTIVE
Last updated: 2026-06-13

Tài liệu này mô tả các test case quan trọng của `checkin-service`. Service này chịu trách nhiệm online scan, offline snapshot, offline sync, conflict recording và audit history.

## 1. Mục tiêu kiểm thử

`checkin-service` phải đảm bảo các nguyên tắc sau:

1. **Online verification:** Vé được xác thực thông qua `e-ticket-service`, không tự suy đoán ticket state.
2. **First-server-wins:** Khi nhiều thiết bị scan offline cùng một vé, batch nào sync lên server hợp lệ trước sẽ thắng.
3. **Sync idempotency:** Cùng một `syncBatchId` gửi lại nhiều lần phải trả về cached result, không xử lý lại.
4. **Accurate error mapping:** Phân biệt `INVALID_QR_TOKEN` với `ETICKET_SERVICE_UNAVAILABLE`.
5. **Security:** Staff id lấy từ JWT subject, không lấy từ header spoofable.

## 2. Các loại kiểm thử (Test Types)

Hiện service này có **4 loại test chính**. Mỗi loại trả lời một câu hỏi khác nhau:

| Loại test | Câu hỏi cần trả lời | Cách test | File liên quan |
|---|---|---|---|
| **Service/Business Test** | Flow scan/sync có đúng business rule không? | Gọi trực tiếp method của `CheckinService`, mock `ETicketClient`, kiểm tra response và audit DB | `CheckinServiceTest` |
| **Integration/Persistence Test** | Lưu `sync_batches`, `checkin_events`, conflict và unique constraint có đúng không? | Chạy với H2 in-memory database, dùng repository thật và transaction thật | `CheckinServiceTest` |
| **Concurrency Test** | Duplicate sync cùng lúc có tạo batch/log trùng không? | Dùng `ExecutorService` + `CountDownLatch` để gửi cùng một `syncBatchId` trên nhiều thread | `CheckinServiceTest` |
| **Security/Controller Test** | API có chặn đúng JWT/role và chống spoof staff id không? | Dùng `MockMvc` gửi HTTP request giả lập với token hợp lệ, token sai role, không token, và spoof header | `CheckinControllerSecurityTest` |

### 2.1. Service/Business Test được test ra sao

- Test gọi thẳng method service, ví dụ `scan`, `getSnapshot`, `sync`.
- `ETicketClient` được mock để giả lập e-ticket trả vé hợp lệ, vé đã check-in, QR sai, hoặc service outage.
- Test assert response và audit row trong `checkin_events`.

### 2.2. Integration/Persistence Test được test ra sao

- Test chạy với H2 in-memory database để không phụ thuộc PostgreSQL local.
- Repository thật được dùng để kiểm tra insert/update `sync_batches`, `checkin_events`, và conflict.
- Các case quan trọng: duplicate `syncBatchId`, cached result payload, audit cho accepted/rejected scan.

### 2.3. Concurrency Test được test ra sao

- Test tạo nhiều thread bằng `ExecutorService`.
- `CountDownLatch` giữ các thread lại, sau đó thả cùng lúc để giả lập mobile retry đồng thời.
- Kỳ vọng chỉ một request xử lý batch; request còn lại lấy cached result, không ghi duplicate.

### 2.4. Security/Controller Test được test ra sao

- Test không gọi service trực tiếp mà đi qua HTTP layer bằng `MockMvc`.
- JWT được giả lập với role `STAFF`, `CHECKIN_STAFF`, `ADMIN`, customer role, hoặc không token.
- Test kiểm tra authorization và chống giả mạo `X-User-Id`.

## 3. Test suite chính

| Test class | Mục tiêu |
|---|---|
| `CheckinServiceTest` | Online scan, offline sync, conflict, idempotency, downstream error mapping |
| `CheckinControllerSecurityTest` | JWT, role check, chống spoof header |

## 4. Test cases quan trọng

### 3.1. Online scan accepted

- **Tình huống:** Staff scan QR ticket hợp lệ.
- **Cơ chế:** Mock `ETicketClient` trả ticket `ISSUED`, sau đó check-in thành công.
- **Kỳ vọng:** Response trả `ACCEPTED`, DB có audit row trong `checkin_events`.

### 3.2. Online scan duplicate/cancelled/refunded/wrong event

- **Tình huống:** Staff scan vé đã dùng, bị hủy, refund, hoặc vé của concert khác.
- **Cơ chế:** Mock trạng thái ticket/downstream response tương ứng.
- **Kỳ vọng:** Trả stable result code như `DUPLICATE_REJECTED`, `CANCELLED_TICKET`, `REFUNDED_TICKET`, `WRONG_EVENT`, và ghi audit row.

### 3.3. Invalid QR vs e-ticket outage

- **Tình huống:** QR không tồn tại hoặc e-ticket bị down/timeout.
- **Cơ chế:** Mock `ETicketClient` trả not found hoặc throw outage exception.
- **Kỳ vọng:** QR sai trả `INVALID_QR_TOKEN`; downstream lỗi trả `ETICKET_SERVICE_UNAVAILABLE`.

### 3.4. Snapshot download

- **Tình huống:** Staff tải snapshot cho concert để scan offline.
- **Cơ chế:** `checkin-service` gọi `e-ticket-service` và map response.
- **Kỳ vọng:** Payload dùng field `tickets`, không dùng field cũ `tokens`.

### 3.5. Offline sync idempotency

- **Tình huống:** Mobile gửi cùng một `syncBatchId` nhiều lần.
- **Cơ chế:** Gọi `checkinService.sync(req)` nhiều lần với cùng batch id.
- **Kỳ vọng:** Lần sau trả cached payload, không tạo duplicate `sync_batches`, không xử lý lại item.

### 3.6. Offline conflict resolution

- **Tình huống:** Hai thiết bị cùng scan offline một QR token, rồi sync lần lượt.
- **Cơ chế:** Batch đầu tiên xử lý thành công; batch sau gặp ticket đã `CHECKED_IN`.
- **Kỳ vọng:** Batch đầu có item trong `accepted`; batch sau có item trong `conflicts` với reason `DUPLICATE_REJECTED`.

### 3.7. Concurrent duplicate sync

- **Tình huống:** Do network retry, cùng một batch được gửi đồng thời trên nhiều thread.
- **Cơ chế:** Dùng `ExecutorService` và `CountDownLatch` tạo race condition.
- **Kỳ vọng:** Chỉ một batch được xử lý. Các request còn lại nhận cached result. DB không có duplicate batch.

### 3.8. Security

- **Tình huống:** Unauthenticated user hoặc customer role gọi `/api/checkin/**`.
- **Kỳ vọng:** Bị reject. Staff/admin được phép. Header `X-User-Id` không thay đổi staff id ghi vào audit.

## 5. Chạy kiểm thử

Chạy toàn bộ test của service:

```powershell
.\mvnw.cmd clean test
```

Chạy service/business/integration/concurrency tests:

```powershell
.\mvnw.cmd -Dtest=CheckinServiceTest test
```

Chạy security/controller tests:

```powershell
.\mvnw.cmd -Dtest=CheckinControllerSecurityTest test
```

Ghi evidence từ thư mục `tickefy-backend/services/checkin-service`:

```powershell
.\mvnw.cmd clean test *> ..\..\evidence\checkin-service\mvn-test.log
.\mvnw.cmd -Dtest=CheckinServiceTest test *> ..\..\evidence\checkin-service\concurrency-test.log
.\mvnw.cmd -Dtest=CheckinControllerSecurityTest test *> ..\..\evidence\checkin-service\security-test.log
```

## 6. Checklist trước khi sửa service

- Không tự update ticket state trong `checkin-service`.
- Không nuốt e-ticket outage thành `INVALID_QR_TOKEN`.
- Không lấy staff id từ request body/header.
- Không đổi sync replay thành xử lý lại item.
- Không bỏ audit row cho rejected scan.
- Không sửa applied Flyway migration; tạo migration mới nếu cần.
