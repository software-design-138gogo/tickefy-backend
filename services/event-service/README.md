# Event Service

> **Owner:** Dương  
> **Port:** 8082  
> **API Gateway path:** `/api/concerts/**` → `/concerts/**`

## Trách nhiệm

Quản lý thông tin concert, artist, venue. Cung cấp API cho BTC tạo/sửa/cancel concert, và cho user xem danh sách/chi tiết concert.

## Endpoints

| Method | Path | Mô tả | Auth |
|--------|------|-------|------|
| GET | `/concerts` | Danh sách concert (pageable, filter by status) | Public |
| GET | `/concerts/{id}` | Chi tiết concert (venue, artists, ticketTypes) | Public |
| POST | `/concerts` | Tạo concert mới | BTC (X-User-Id header) |
| PUT | `/concerts/{id}` | Cập nhật thông tin concert | BTC |
| POST | `/concerts/{id}/publish` | Chuyển trạng thái → PUBLISHED | BTC |
| POST | `/concerts/{id}/cancel` | Chuyển trạng thái → CANCELLED | BTC |
| GET | `/health` | Health check | Public |

## Status Lifecycle

```
DRAFT → PUBLISHED → COMPLETED
            ↓
        CANCELLED
```

## Events (RabbitMQ)

- **Publish:** `ConcertPublished`, `ConcertCancelled` *(TODO Phase 2)*
- **Consume:** `ArtistBioGenerated` *(TODO Phase 2)*

## Database

- **DB name:** `tickefy_event`
- **Tables:** `venues`, `artists`, `concerts`, `concert_artists`, `concert_zones`
- **Migration tool:** Flyway (`db/migration/`)

## Khởi động local

```bash
cd tickefy-infrastructure/local
./scripts/up.sh dev
# Service available at: http://localhost:8082
# Swagger UI: http://localhost:8082/swagger-ui.html
```

## Cấu trúc package

```
com.tickefy.event/
├── EventServiceApplication.java
├── common/          # exception, response, logging (DO NOT MODIFY)
├── config/          # OpenAPI, WebConfig
├── database/        # DatabaseConfig
├── modules/
│   ├── concert/     # Concert, ConcertZone, ConcertService, ConcertController
│   ├── artist/      # Artist, ArtistRepository
│   └── venue/       # Venue, VenueRepository
└── shared/
```

## Tự động hóa việc chạy Service ở local để test thử API bằng Swagger (Không cần gõ biến thủ công)

Để không phải vất vả gõ dòng lệnh `$env:DB_HOST=...` mỗi lần khởi động Service, tôi đã viết sẵn một script PowerShell tên là **`run-local.ps1`** nằm ngay trong thư mục `event-service`.

Từ nay về sau, ở Bước 2, bạn chỉ cần gõ đúng 1 lệnh ngắn gọn này trong PowerShell:
```powershell
cd d:\Course_IT_HCMUS\Nam_3\SoftwareDes\Project-TicketBox\tickefy-backend\services\event-service
.\run-local.ps1
```
*Script này sẽ tự động tìm file `.env`, bóc tách toàn bộ biến môi trường đưa vào bộ nhớ của terminal, rồi tự động gọi `.\mvnw spring-boot:run` cho bạn!*