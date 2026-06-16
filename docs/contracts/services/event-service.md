---
title: Service Specification - event-management-service
status: PROPOSED
version: 1.0
owner: Dương
reviewers: [Hiệp, Hoàng, Hòa]
lastUpdated: 2026-06-16
---

# Service Specification — `event-management-service`

## 1. Identity

| Item | Value |
|---|---|
| Service name | event-management-service |
| Owner | Dương |
| Repository | tickefy-backend/services/event-service |
| Internal port | 8082 |
| Public base path | `/api/v1/events` |
| Health check | `/actuator/health` |
| Swagger/OpenAPI | `/swagger-ui.html` |
| Database schema | `event_service` |

## 2. Responsibilities

### Service chịu trách nhiệm

- Quản lý vòng đời của Sự kiện (Concert): Tạo nháp (DRAFT), phát hành (PUBLISHED), và hủy (CANCELLED).
- Quản lý thông tin metadata của Sự kiện: Nghệ sĩ (Artists), Địa điểm (Venues), và Khu vực chỗ ngồi (Zones).
- Lưu trữ URL của sơ đồ ghế ngồi (Seat Map SVG) thông qua cơ chế cấp Pre-signed URL.
- Xử lý lưu lượng truy cập đọc lớn (Read-heavy) từ phía khán giả thông qua hệ thống Caching đa tầng (Two-Tier).

### Service không chịu trách nhiệm

- Không quản lý tồn kho vé, số lượng vé còn lại, hay chống over-selling (Thuộc về `inventory-service`).
- Không xử lý thanh toán (Thuộc về `payment-service`).
- Không sinh mã vé QR hay quản lý vé đã xuất (Thuộc về `ticket-service` / `checkin-service`).
- Không trực tiếp xử lý upload file nhị phân (Chỉ cấp Pre-signed URL cho FE tự đẩy lên S3).

## 3. Data ownership

### Tables owned

| Table | Purpose |
|---|---|
| `concerts` | Dữ liệu gốc về sự kiện (Title, thời gian diễn ra, trạng thái). |
| `artists` | Thông tin nghệ sĩ (Tên, tiểu sử AI sinh ra). |
| `venues` | Địa điểm tổ chức (Sân vận động, sức chứa). |
| `concert_zones` | Các khu vực vé trên sơ đồ (SVIP, VIP). Lưu trữ kèm `seat_map_url`. |
| `outbox_events` | Bảng Outbox phục vụ Transactional Outbox Pattern khi publish sự kiện. |

### Cross-service references

| Field | Source service | Validation strategy |
|---|---|---|
| `created_by` | `auth-service` | Chứa UserId của Admin/Organizer từ JWT Token. Không dùng Foreign Key. |

### Invariants

- Không có cross-service foreign key.
- Service khác không query trực tiếp schema này (PostgreSQL của Event Service là đóng kín).

## 4. Dependencies

### Synchronous dependencies

*Event Service là nguồn dữ liệu gốc (Single Source of Truth), hầu như không gọi đồng bộ (Synchronous) sang service khác để phục vụ luồng chính.*

| Service | Endpoint | Purpose | Timeout | Retry |
|---|---|---|---:|---|
| None | N/A | Không phụ thuộc đồng bộ vào API khác. | N/A | N/A |

### Infrastructure dependencies

| Dependency | Purpose |
|---|---|
| PostgreSQL | Source of Truth lưu trữ cấu trúc Sự kiện. |
| Redis | Two-Tier Caching (L2) cho dữ liệu Read-Heavy, và Distributed Lock (Mutex Lock) chống Stampede. |
| RabbitMQ | Message Broker để gửi đi thông báo (`ConcertPublished`, `ConcertCancelled`). |
| Object Storage (S3/MinIO) | Lưu trữ file sơ đồ ghế SVG. (Event Service chỉ gọi SDK để sinh Pre-signed URL, FE Admin sẽ gửi thẳng file lên S3). |

## 5. Public APIs (Gateway Exposes)

| Method | Path | Role | Description | Contract |
|---|---|---|---|---|
| GET | `/api/v1/concerts` | PUBLIC | Lấy danh sách concert (Có phân trang, phục vụ trang chủ). Caching 2 tầng. | `event-contract.md` |
| GET | `/api/v1/concerts/{id}` | PUBLIC | Chi tiết concert và các loại vé. | `event-contract.md` |
| POST | `/api/v1/admin/concerts` | ADMIN/ORG | Tạo mới sự kiện (Trạng thái DRAFT). | |
| PUT | `/api/v1/admin/concerts/{id}` | ADMIN/ORG | Chỉnh sửa metadata sự kiện. | |
| POST | `/api/v1/admin/concerts/{id}/publish`| ADMIN/ORG | Chuyển trạng thái sang PUBLISHED. | `event-contract.md` |
| POST | `/api/v1/admin/concerts/{id}/cancel` | ADMIN/ORG | Chuyển trạng thái sang CANCELLED. | `event-contract.md` |
| GET | `/api/v1/admin/concerts/upload-url` | ADMIN/ORG | Cấp Pre-signed URL cho FE đẩy file SVG lên S3. | Trả về chuỗi URL. |

## 6. Internal APIs (Service-to-Service, Không qua Gateway)

| Method | Path | Caller | Description | Contract |
|---|---|---|---|---|
| GET | `/internal/concerts/{id}` | Inventory | Inventory lấy thông tin Concert + Check trạng thái `PUBLISHED` để cho phép/không cho phép bán vé. (Kèm Bearer Token). | Dùng chung Payload với Public GET Detail. |

## 7. Events published

*Lưu ý: Publish thông qua Outbox Pattern.*

| Event | Routing key | When | Consumers | Contract |
|---|---|---|---|---|
| `ConcertPublished` | `concert.published` | Khi Admin gọi API publish. | Inventory (Mở bán), Cache (Pre-warm) | `event-contract.md` |
| `ConcertCancelled` | `concert.cancelled` | Khi Admin gọi API cancel. | Order (Refund), Inventory (Khóa bán) | `event-contract.md` |

## 8. Events consumed

| Event | Producer | Queue | Behavior | Idempotency key |
|---|---|---|---|---|
| `ArtistBioGenerated` | `ai-bio-service` | `event-service.artist-bio.queue` | Cập nhật cột `bio` trong bảng `artists`. | `eventId` + `artistId` |

## 9. State machines

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> PUBLISHED : Admin bấm Publish
    PUBLISHED --> CANCELLED : Admin bấm Cancel
    PUBLISHED --> COMPLETED : Quá thời gian sự kiện
```

### Transition table

| Current | Action/Event | Next | Side effects |
|---|---|---|---|
| DRAFT | Admin Publish | PUBLISHED | Lưu DB + Ghi `ConcertPublished` vào bảng Outbox. |
| PUBLISHED | Admin Cancel | CANCELLED | Lưu DB + Ghi `ConcertCancelled` vào bảng Outbox. |

## 10. Reliability

### Idempotency
- Consuming `ArtistBioGenerated` sử dụng phép Update DB idempotency (Ghi đè bio cũ). Update nhiều lần cùng một text không làm sai dữ liệu.

### Retry & Transaction boundaries
- **Transactional Outbox Pattern**: Hành động đổi status sự kiện và việc xuất message MQ được gộp chung trong 1 Database Transaction (Cập nhật bảng `concerts` + Insert `outbox_events`). Một worker `@Scheduled` (Drainer) sẽ quét outbox định kỳ và publish lên RabbitMQ, sau đó mark sent. Điều này đảm bảo Eventual Consistency.

## 11. Cache

*Áp dụng chuẩn `caching.md`.*

| Key pattern | Data | TTL | Invalidation |
|---|---|---:|---|
| `cache:events:list` | Danh sách sự kiện | 1h + Jitter | Xóa chủ động (Evict L2) + Bắn Redis Pub/Sub xóa L1 khi có thay đổi. |
| `cache:events:{id}` | Chi tiết Concert | 24h + Jitter | Evict chủ động khi update. |
| `cache:events:{id}:null` | Kết quả rỗng (Chống Penetration) | 30s - 60s | Tự hết hạn (Auto-expire). |

- **Bloom Filter:** [Nice-to-have] Tạm thời defer, ưu tiên dùng Cache Null để chống Penetration.
- **Mutex Lock:** Dùng Redisson lock 2s khi Cache Miss để chặn Cache Stampede.

## 12. Security

- Authentication: Validate JWT qua Gateway. Forward role xuống Event Service.
- Authorization: Các endpoint `/api/v1/admin/*` yêu cầu quyền `ORGANIZER` hoặc `ADMIN`.
- Logging mask: Không yêu cầu che giấu dữ liệu sự kiện vì bản chất là Public. Tránh log JWT Token.

## 13. Environment variables

| Variable | Required | Example | Description |
|---|---|---|---|
| `POSTGRES_URL` | Yes | `jdbc:postgresql://db:5432/event` | DB Connection |
| `REDIS_URL` | Yes | `redis://redis:6379` | Cache connection |
| `S3_BUCKET_NAME` | Yes | `tickefy-media` | Bucket cấp Pre-signed URL |
| `AWS_ACCESS_KEY` | Yes | `...` | MinIO/S3 Creds cho SDK |

## 14. Observability
- Logs: Gom log ELK. Focus vào các action thay đổi trạng thái (Publish/Cancel).
- Metrics: Đếm tỷ lệ Cache Hit/Miss cho endpoint GET Concerts.
- Alerts: Cảnh báo nếu Outbox Worker fail nhiều lần liên tục hoặc Outbox queue tồn đọng > 100 records.

## 15. Failure scenarios

| Scenario | Expected behavior | Error/event |
|---|---|---|
| RabbitMQ down lúc Publish | Trả về 200 OK cho Admin bình thường. Sự kiện nằm an toàn ở bảng `outbox_events`. | Drainer tự động gửi bù khi MQ phục hồi. (Eventual Consistency). Không Fail-fast. |
| Redis down | Circuit Breaker mở. Dữ liệu tĩnh sẽ đọc tạm từ L1 (Caffeine). Fallback chặn query dội thẳng vào DB. | HTTP 503 nếu quá tải L1 Miss. |
| S3 / MinIO down | API cấp Pre-signed URL trả về lỗi. Admin không upload được hình SVG. | HTTP 500. Báo Admin thử lại sau. |

## 16. Integration acceptance criteria
- [ ] Swagger Docs đầy đủ.
- [ ] Tạo được Concert + Chuyển trạng thái sang PUBLISHED thành công.
- [ ] Outbox table ghi nhận bản ghi khi Publish, và Worker quét thành công.
- [ ] Gọi API nhận được Pre-signed URL hợp lệ, FE PUT thẳng lên S3 thành công.
- [ ] Cache Hit/Miss hoạt động đúng với Mutex Lock.

## 17. Open questions
- Frontend Web (Hiệp) xác nhận sẽ handle luồng upload 3 bước (Xin URL -> PUT S3 -> Submit Backend) cho admin chưa?
