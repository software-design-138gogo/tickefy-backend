# Event Service - Unit Test Guide

Thư mục này chứa toàn bộ các bài Unit Test tự động cho `event-service`, đảm bảo tính đúng đắn của các logic quan trọng (Security, Caching, Controller, Service).

## Cấu trúc thư mục Test
- **Controller Layer:** Chứa các test sử dụng `MockMvc` để kiểm tra phân quyền, xác thực JWT, và Ownership.
- **Service Layer:** Chứa các test sử dụng `Mockito` để kiểm tra Cache (Two-Tier Cache L1/L2) và các luồng Business Logic (CRUD).
- **Outbox Consumer:** Chứa test xử lý thông điệp bất đồng bộ (RabbitMQ).

## Cách chạy Test tự động

Bạn có 2 cách để chạy toàn bộ Unit Test:

### Cách 1: Sử dụng Script (Nhanh nhất)
Mở Terminal (PowerShell) tại chính thư mục `src/test` này và chạy script:
```powershell
.\run-unit-tests.ps1
```

### Cách 2: Chạy lệnh Maven thủ công
Mở Terminal tại thư mục gốc của `event-service` (ngang hàng với file `pom.xml`) và chạy lệnh:
```powershell
.\mvnw clean test
```

## Báo cáo (Report)
Sau khi test chạy xong, nếu muốn xem báo cáo chi tiết, bạn có thể mở các file báo cáo của thư viện `surefire` tại đường dẫn:
`event-service\target\surefire-reports`
