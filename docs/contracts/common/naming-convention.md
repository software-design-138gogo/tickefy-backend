---
title: Naming Convention
status: DRAFT
version: 1.0
owner: BE Lead
reviewers:
lastUpdated: 2026-06-16
---

# Naming Convention

## 1. Mục tiêu

Tài liệu này định nghĩa quy ước đặt tên dùng chung cho toàn bộ hệ thống Tickefy.

Mục tiêu:

* Tránh một đối tượng có nhiều tên khác nhau giữa các service.
* Giúp API, event, database và source code khớp nhau.
* Giảm lỗi khi tích hợp backend, frontend, mobile và infrastructure.
* Giúp người đọc hiểu ý nghĩa của field mà không cần tra cứu thêm.
* Giữ contract ổn định khi các thành viên code song song.

Các service không được tự tạo naming convention riêng nếu nội dung đã được quy định trong tài liệu này.

---

## 2. Nguyên tắc chung

* Tên phải mô tả đúng ý nghĩa nghiệp vụ.
* Một khái niệm chỉ sử dụng một tên canonical.
* Không dùng từ viết tắt khó hiểu.
* Không dùng cùng một tên cho hai khái niệm khác nhau.
* Không đổi tên field, enum, endpoint hoặc event đã `FROZEN` nếu chưa cập nhật version contract.
* Tên trong public contract phải ưu tiên tính rõ ràng hơn độ ngắn.
* Source code, API, event và database phải có mapping nhất quán.

Ví dụ:

```text
API/Event/Java/TypeScript: concertId
Database:                 concert_id
```

---

## 3. Domain terminology

### 3.1. Concert và event

Trong Tickefy:

* `concert` là đối tượng nghiệp vụ đại diện cho một buổi biểu diễn.
* `event` được dùng cho message/domain event truyền qua RabbitMQ.

Do đó:

```text
concertId = ID của concert
eventType = loại integration event
messageId = ID của một message occurrence
```

Không sử dụng `eventId` để đại diện cho concert trong API hoặc event payload mới.

Đúng:

```json
{
  "concertId": "concert-uuid"
}
```

Không dùng:

```json
{
  "eventId": "concert-uuid"
}
```

Tên service vẫn là:

```text
event-service
```

Tên service này phản ánh bounded context hiện tại, nhưng resource nghiệp vụ trong contract là `concert`.

### 3.2. Tên canonical của service

| Service           | Tên canonical           |
| ----------------- | ----------------------- |
| Auth & User       | `auth-service`          |
| Event / Concert   | `event-service`         |
| Ticket Inventory  | `inventory-service`     |
| Order / Booking   | `order-service`         |
| Payment           | `payment-service`       |
| Notification      | `notification-service`  |
| E-Ticket          | `ticket-service`        |
| Check-in          | `checkin-service`       |
| AI Bio            | `ai-bio-service`        |
| CSV/VIP Ingestion | `csv-ingestion-service` |

Tên canonical được dùng trong:

* Docker Compose.
* Event Envelope `source`.
* Log field `service`.
* Queue naming.
* Service discovery.
* Tài liệu integration.

Không thêm số instance hoặc tên thành viên vào tên service.

---

## 4. ID naming

### 4.1. Quy tắc chung

ID trong API, event và source code sử dụng:

```text
<entityName>Id
```

ID cross-service sử dụng UUID string.

| Đối tượng             | Field chuẩn            |
| --------------------- | ---------------------- |
| User                  | `userId`               |
| Organizer             | `organizerId`          |
| Staff                 | `staffId`              |
| Concert               | `concertId`            |
| Artist                | `artistId`             |
| Venue                 | `venueId`              |
| Ticket type           | `ticketTypeId`         |
| Reservation           | `reservationId`        |
| Order                 | `orderId`              |
| Order item            | `orderItemId`          |
| Payment               | `paymentId`            |
| Payment attempt       | `paymentAttemptId`     |
| Ticket                | `ticketId`             |
| Check-in record       | `checkinId`            |
| Device                | `deviceId`             |
| Sync batch            | `syncBatchId`          |
| Snapshot              | `snapshotId`           |
| Conflict              | `conflictId`           |
| AI job                | `jobId` hoặc `aiJobId` |
| Import job            | `importJobId`          |
| VIP guest             | `vipGuestId`           |
| Notification          | `notificationId`       |
| RabbitMQ message      | `messageId`            |
| Business-flow tracing | `correlationId`        |
| Parent message        | `causationId`          |
| HTTP request          | `requestId`            |

### 4.2. `jobId` và `importJobId`

Trong phạm vi một service, có thể dùng:

```text
jobId
```

Trong contract có nhiều loại job hoặc khi dữ liệu đi xuyên service, dùng tên cụ thể:

```text
aiJobId
importJobId
reconciliationJobId
```

### 4.3. Public token

Token public không dùng hậu tố `Id` nếu nó không phải entity identifier.

Ví dụ:

```text
qrToken
accessToken
refreshToken
idempotencyKey
ticketCode
```

Không dùng:

```text
qrId
tokenId
```

trừ khi đối tượng thật sự có một entity ID riêng.

---

## 5. Field naming

### 5.1. API, event và source code

Sử dụng `camelCase`.

Ví dụ:

```text
concertId
ticketTypeId
saleStartAt
totalAmount
errorReportObjectKey
```

### 5.2. Collection

Tên collection dùng danh từ số nhiều.

```text
items
tickets
roles
notifications
acceptedItems
rejectedItems
conflicts
```

Không dùng tên mơ hồ:

```text
list
dataList
recordsData
resultList
```

### 5.3. Count và quantity

* `quantity`: số lượng được yêu cầu hoặc thuộc một operation.
* `count`: số lượng đã được đếm hoặc trạng thái tổng hợp.
* `total`: tổng giá trị hoặc tổng số bản ghi.

Ví dụ:

```text
quantity
availableCount
reservedCount
soldCount
itemCount
totalRows
totalAmount
```

Không trộn lẫn:

```text
availableQuantity
reservedQuantity
```

nếu cùng một aggregate đang sử dụng hậu tố `Count`.

### 5.4. URL và object storage

* URL hoàn chỉnh: hậu tố `Url`.
* Object Storage key/path nội bộ: hậu tố `ObjectKey`.
* Tên file: hậu tố `FileName`.

Ví dụ:

```text
paymentUrl
imageUrl
errorReportUrl
pdfObjectKey
csvObjectKey
fileName
```

Không dùng `fileUrl` nếu giá trị thực tế chỉ là object key.

---

## 6. Boolean naming

Boolean phải thể hiện rõ câu hỏi đúng/sai và sử dụng một trong các prefix:

```text
is
has
can
should
```

Ví dụ:

```text
isActive
isOffline
isLast
hasAttachment
canRetry
shouldNotify
```

Không dùng boolean mơ hồ:

```text
active
offline
retry
deleted
```

Trong database, tên tương ứng dùng `snake_case`:

```text
is_active
is_offline
has_attachment
```

---

## 7. Time and date

### 7.1. Định dạng

Thời gian trong API và event sử dụng UTC ISO-8601:

```text
2026-06-16T10:00:00Z
```

Trong PostgreSQL sử dụng:

```text
TIMESTAMPTZ
```

### 7.2. Tên field

Thời điểm cụ thể kết thúc bằng `At`.

```text
createdAt
updatedAt
occurredAt
expiresAt
paidAt
issuedAt
scannedAt
syncedAt
completedAt
cancelledAt
refundedAt
```

Khoảng thời gian hoặc thời lượng kết thúc bằng đơn vị:

```text
timeoutMs
durationMs
retryDelaySeconds
reservationTtlMinutes
```

Ngày không có thời gian kết thúc bằng `Date`:

```text
birthDate
reportDate
```

Không dùng tên thiếu ý nghĩa:

```text
time
date
timestamp
expireTime
```

Ngoại lệ: trường `timestamp` trong tài liệu cũ phải được chuyển dần sang tên cụ thể như `occurredAt` hoặc `createdAt`.

---

## 8. Money and currency

### 8.1. Giá trị tiền

Tiền VND được lưu và truyền dưới dạng integer.

```json
{
  "unitPrice": 1500000,
  "subtotal": 3000000,
  "totalAmount": 3000000,
  "currency": "VND"
}
```

Không sử dụng:

* `float`.
* `double`.
* Giá trị có dấu phân cách.
* Chuỗi đã format để làm dữ liệu nghiệp vụ.

Không dùng:

```json
{
  "amount": "1,500,000 VND"
}
```

### 8.2. Tên field

| Ý nghĩa             | Field          |
| ------------------- | -------------- |
| Số tiền chung       | `amount`       |
| Giá một đơn vị      | `unitPrice`    |
| Tổng một order item | `subtotal`     |
| Tổng order          | `totalAmount`  |
| Số tiền hoàn        | `refundAmount` |
| Mã tiền tệ          | `currency`     |

`currency` sử dụng mã ISO dạng `UPPER_SNAKE_CASE` hoặc uppercase code:

```text
VND
USD
```

---

## 9. Enum and status

### 9.1. Format

Enum value sử dụng `UPPER_SNAKE_CASE`.

Ví dụ:

```text
PAYMENT_PENDING
PAYMENT_FAILED
CHECKED_IN
COMPLETED_WITH_ERRORS
DUPLICATE_REJECTED
```

### 9.2. Status field

Tên field trạng thái mặc định là:

```text
status
```

Khi một payload có nhiều loại trạng thái, phải ghi rõ phạm vi:

```text
orderStatus
paymentStatus
ticketStatus
jobStatus
deliveryStatus
```

### 9.3. Stable enum

Sau khi contract được `FROZEN`:

* Không đổi tên enum.
* Không tái sử dụng enum cũ với ý nghĩa mới.
* Không đổi `SUCCESS` thành `SUCCEEDED` trong cùng contract.
* Không đổi `CANCELLED` thành `CANCELED` tùy từng service.
* Breaking change phải cập nhật version contract.

### 9.4. Kết quả và lỗi

Business result hoặc error code sử dụng `UPPER_SNAKE_CASE`.

Ví dụ:

```text
ACCEPTED
OFFLINE_ACCEPTED
DUPLICATE_REJECTED
INVALID_QR_TOKEN
RESERVATION_EXPIRED
PAYMENT_GATEWAY_UNAVAILABLE
```

Client phải branch bằng code, không branch bằng message.

---

## 10. REST API naming

### 10.1. Base path

MVP sử dụng:

```text
/api/<resource>
```

Không tự thêm `/v1` khi team chưa chốt API version mới.

Breaking version trong tương lai:

```text
/api/v2/<resource>
```

### 10.2. Resource naming

Resource sử dụng:

* Chữ thường.
* Danh từ số nhiều.
* Dấu gạch ngang cho cụm từ.
* Không dùng động từ cho CRUD thông thường.

Ví dụ:

```text
/api/concerts
/api/ticket-types
/api/reservations
/api/orders
/api/payments
/api/tickets
/api/checkins
/api/notifications
/api/ai-bio/jobs
/api/vip-import/jobs
```

### 10.3. Resource identifier

Dùng path parameter mô tả rõ entity:

```http
GET /api/concerts/{concertId}
GET /api/orders/{orderId}
GET /api/tickets/{ticketId}
GET /api/ai-bio/jobs/{jobId}
```

Không dùng tên chung:

```http
GET /api/orders/{id}
```

trong tài liệu contract, vì `{orderId}` rõ nghĩa hơn.

### 10.4. Action endpoint

Khi operation không phù hợp với CRUD, dùng action rõ ràng ở cuối path:

```http
POST  /api/concerts/{concertId}/publish
POST  /api/concerts/{concertId}/cancel
POST  /api/reservations/{reservationId}/commit
POST  /api/reservations/{reservationId}/release
POST  /api/payments/{paymentId}/retry
POST  /api/ai-bio/jobs/{jobId}/retry
PATCH /api/notifications/{notificationId}/read
```

Không dùng:

```http
POST /api/publishConcert
POST /api/doPayment
POST /api/processTicket
```

### 10.5. Query parameters

Query parameter dùng `camelCase`.

```http
GET /api/concerts?page=0&size=20&sort=eventStartAt,asc
GET /api/orders?userId=<uuid>&status=PAID
```

Tên filter phải khớp với field trong contract.

---

## 11. API request and response models

### 11.1. DTO naming

Java:

```text
CreateOrderRequest
CreateOrderResponse
OrderDetailResponse
UpdateConcertRequest
ReservationResponse
```

TypeScript:

```text
CreateOrderRequest
CreateOrderResponse
OrderDetail
ApiResponse<T>
```

Không dùng tên chung chung:

```text
OrderDto
DataDto
ResponseDto
RequestData
```

nếu model có mục đích cụ thể.

### 11.2. Request và response separation

Không dùng chung một entity/model cho:

* Database entity.
* API request.
* API response.
* Event payload.

Ví dụ:

```text
OrderEntity
CreateOrderRequest
OrderResponse
OrderPaidPayload
```

### 11.3. Pagination

Response phân trang sử dụng:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total": 150,
  "totalPages": 8
}
```

Không trộn với format khác như:

```text
content
pageable
pageNumber
totalElements
```

trong contract mới.

---

## 12. Error naming

Error code sử dụng `UPPER_SNAKE_CASE` và mô tả nguyên nhân nghiệp vụ.

Ví dụ:

```text
VALIDATION_ERROR
CONCERT_NOT_FOUND
TICKET_SOLD_OUT
PER_USER_LIMIT_EXCEEDED
RESERVATION_EXPIRED
PAYMENT_GATEWAY_UNAVAILABLE
DUPLICATE_REJECTED
INVALID_QR_TOKEN
```

Không sử dụng:

```text
ERROR_001
INVALID
FAILED
SOMETHING_WENT_WRONG
```

Quy tắc phân biệt:

```text
INVALID_TOKEN     → JWT/auth token không hợp lệ
INVALID_QR_TOKEN  → QR token không hợp lệ
```

Message hiển thị có thể thay đổi hoặc dịch ngôn ngữ, nhưng `error.code` phải ổn định.

Tên exception trong backend nên bám theo error code:

```text
ConcertNotFoundException
ReservationExpiredException
PaymentGatewayUnavailableException
```

---

## 13. Event and RabbitMQ naming

### 13.1. Event type

Event type sử dụng `PascalCase` và diễn tả sự kiện đã xảy ra.

```text
ConcertPublished
PaymentSucceeded
OrderPaid
TicketsIssued
TicketCheckedIn
ArtistBioGenerated
VipGuestImportCompleted
```

Không dùng command làm event:

```text
PublishConcert
ProcessPayment
IssueTickets
```

### 13.2. Exchange

Integration event exchange:

```text
tickefy.events
```

Exchange type:

```text
topic
```

### 13.3. Routing key

Routing key sử dụng chữ thường và dấu chấm:

```text
<domain>.<event>
```

Ví dụ:

```text
concert.published
concert.cancelled
payment.succeeded
payment.failed
order.paid
order.expired
tickets.issued
ticket.checked-in
artist-bio.generated
vip-guest-import.completed
```

### 13.4. Queue

Queue sử dụng:

```text
{consumer-service-prefix}.{event-name}
```

Ví dụ:

```text
order.payment-succeeded
inventory.order-paid
ticket.order-paid
notification.tickets-issued
event.artist-bio-generated
checkin.vip-guest-import-completed
```

Phần prefix của queue là consumer, không phải producer.

### 13.5. Dead Letter Queue

```text
{queue}.dlq
```

Ví dụ:

```text
ticket.order-paid.dlq
notification.tickets-issued.dlq
```

### 13.6. Event fields

Event Envelope sử dụng:

```text
messageId
eventType
eventVersion
source
occurredAt
correlationId
causationId
payload
```

Không tạo biến thể khác như:

```text
eventName
messageType
body
data
producerName
createdTime
```

---

## 14. Database naming

### 14.1. Schema

Schema sử dụng:

```text
<domain>_schema
```

Ví dụ:

```text
auth_schema
event_schema
inventory_schema
order_schema
payment_schema
ticket_schema
checkin_schema
notification_schema
ai_bio_schema
csv_schema
```

### 14.2. Table và column

Table và column dùng `snake_case`.

Table dùng danh từ số nhiều:

```text
users
orders
order_items
payment_transactions
ticket_reservations
checkin_events
import_jobs
```

Column:

```text
concert_id
ticket_type_id
total_amount
created_at
updated_at
```

### 14.3. Primary key

Primary key mặc định:

```text
id
```

Trong API hoặc application model, map thành field cụ thể:

```text
orders.id → orderId
tickets.id → ticketId
```

### 14.4. Foreign key

Foreign key trong cùng service:

```text
fk_<child_table>_<parent_table>
```

Ví dụ:

```text
fk_order_items_orders
```

Không tạo foreign key xuyên service.

Cross-service reference chỉ lưu UUID:

```sql
concert_id UUID NOT NULL
```

và ghi comment hoặc tài liệu xác định source service.

### 14.5. Index

Quy ước:

```text
idx_<table>_<columns>
```

Ví dụ:

```text
idx_orders_user_id
idx_orders_status
idx_tickets_concert_id_status
```

Unique constraint:

```text
uq_<table>_<columns>
```

Ví dụ:

```text
uq_tickets_order_item_id
uq_payment_transactions_idempotency_key
```

Check constraint:

```text
ck_<table>_<rule>
```

Ví dụ:

```text
ck_tickets_status
ck_inventory_non_negative
```

### 14.6. Migration

Flyway migration:

```text
V<version>__<description>.sql
```

Ví dụ:

```text
V1__create_orders.sql
V2__add_order_idempotency_key.sql
V3__create_order_status_history.sql
```

Description dùng chữ thường và dấu gạch dưới.

---

## 15. Redis key naming

Redis key sử dụng dấu hai chấm để phân cấp:

```text
<domain>:<purpose>:<identifier>
```

Ví dụ:

```text
cache:concerts:list
cache:concerts:{concertId}
inventory:remaining:{ticketTypeId}
reservation:{reservationId}
payment:idempotency:{idempotencyKey}
order:idempotency:{idempotencyKey}
auth:blacklist:{jti}
rate-limit:{userId}:{endpoint}
```

Quy tắc:

* Dùng chữ thường.
* Không chứa khoảng trắng.
* Không đưa token hoặc dữ liệu nhạy cảm trực tiếp vào key.
* Mọi key tạm thời phải có TTL.
* Key pattern phải được document trong Service Specification.
* Không dùng key quá chung như `cache:data` hoặc `lock:1`.

Distributed lock:

```text
lock:<domain>:<resourceId>
```

Ví dụ:

```text
lock:inventory:{ticketTypeId}
lock:payment:{paymentId}
```

---

## 16. Environment variables

Biến môi trường sử dụng `UPPER_SNAKE_CASE`.

Ví dụ:

```text
SERVER_PORT
DB_URL
DB_USERNAME
DB_PASSWORD
REDIS_HOST
REDIS_PORT
RABBITMQ_HOST
RABBITMQ_USERNAME
RABBITMQ_PASSWORD
MINIO_ENDPOINT
AI_PROVIDER_API_KEY
PAYMENT_CALLBACK_URL
```

Biến dành riêng cho service nên có prefix nếu dễ gây trùng:

```text
AI_BIO_MAX_RETRY
CSV_IMPORT_BATCH_SIZE
PAYMENT_PROVIDER_TIMEOUT_MS
RESERVATION_TTL_MINUTES
```

Boolean dùng:

```text
true
false
```

Không commit giá trị secret thật vào:

* Source code.
* `application.yml`.
* Docker Compose.
* `.env.example`.

`.env.example` chỉ chứa placeholder:

```text
DB_PASSWORD=change-me
AI_PROVIDER_API_KEY=
```

---

## 17. Source code naming

### 17.1. Java

| Thành phần      | Quy ước                  | Ví dụ                           |
| --------------- | ------------------------ | ------------------------------- |
| Package         | lowercase                | `com.tickefy.order.application` |
| Class           | PascalCase               | `OrderService`                  |
| Method          | camelCase                | `createOrder`                   |
| Variable        | camelCase                | `reservationId`                 |
| Constant        | UPPER_SNAKE_CASE         | `MAX_RETRY_ATTEMPTS`            |
| Interface       | PascalCase               | `PaymentClient`                 |
| Exception       | `<Reason>Exception`      | `OrderNotFoundException`        |
| Repository      | `<Entity>Repository`     | `OrderRepository`               |
| Controller      | `<Resource>Controller`   | `OrderController`               |
| Event consumer  | `<Event>Consumer`        | `PaymentSucceededConsumer`      |
| Event publisher | `<Domain>EventPublisher` | `OrderEventPublisher`           |

Không thêm prefix `I` vào interface:

```text
PaymentClient
```

thay vì:

```text
IPaymentClient
```

### 17.2. TypeScript

| Thành phần           | Quy ước                                           |
| -------------------- | ------------------------------------------------- |
| Type/interface/class | `PascalCase`                                      |
| Function/variable    | `camelCase`                                       |
| Constant             | `UPPER_SNAKE_CASE`                                |
| Component            | `PascalCase`                                      |
| Hook                 | bắt đầu bằng `use`                                |
| File component       | `PascalCase.tsx` hoặc convention FE đã thống nhất |

Ví dụ:

```text
OrderDetail
CreateOrderRequest
useOrderStatus
TicketQrCard
```

---

## 18. Logging and tracing fields

Structured log sử dụng field `camelCase`.

Các field chuẩn:

```text
timestamp
level
service
requestId
correlationId
messageId
eventType
userId
staffId
concertId
orderId
paymentId
ticketId
deviceId
result
durationMs
```

Không tự tạo biến thể:

```text
request_id
reqId
correlation_id
concert_id
```

trong JSON log application.

Database vẫn dùng `snake_case`; log và API dùng `camelCase`.

Dữ liệu nhạy cảm phải được mask:

```text
qrTokenMasked
emailMasked
phoneMasked
```

Không log:

```text
password
jwt
refreshToken
fullQrToken
providerSecret
```

---

## 19. File and documentation naming

Markdown file dùng chữ thường và dấu gạch ngang.

Ví dụ:

```text
event-envelope.md
naming-convention.md
api-standard.md
purchase-flow.md
offline-checkin-flow.md
```

Service documentation:

```text
services/order-service.md
services/payment-service.md
services/checkin-service.md
```

ADR:

```text
adr-001-use-rabbitmq.md
adr-002-database-per-service.md
```

Không sử dụng khoảng trắng hoặc tên như:

```text
New Document.md
Final_Version_2.md
API Contract Latest.md
```

---

## 20. Quy trình thay đổi naming

Khi cần thay đổi một tên đã được `FROZEN`:

1. Xác định đây là internal change hay contract change.
2. Liệt kê API, event, database và consumer bị ảnh hưởng.
3. Tạo PR cập nhật tài liệu.
4. Có review từ owner của các service phụ thuộc.
5. Nếu breaking, tăng contract version.
6. Cập nhật code, test, OpenAPI và event schema.
7. Giữ backward compatibility trong thời gian chuyển đổi nếu cần.
8. Chỉ xóa tên cũ sau khi tất cả consumer đã chuyển đổi.

Không đổi tên trực tiếp trong code mà không cập nhật contract.

---

## 21. Checklist review

Trước khi freeze API, event hoặc service spec, kiểm tra:

* [ ] Dùng `concertId`, không dùng `eventId` cho concert.
* [ ] ID field có hậu tố `Id`.
* [ ] API/event field dùng `camelCase`.
* [ ] Database dùng `snake_case`.
* [ ] Time field dùng hậu tố `At` và UTC.
* [ ] Money dùng integer VND.
* [ ] Enum và error code dùng `UPPER_SNAKE_CASE`.
* [ ] Event type dùng `PascalCase`.
* [ ] REST resource dùng danh từ số nhiều.
* [ ] RabbitMQ routing key dùng lowercase dot notation.
* [ ] Queue bắt đầu bằng consumer service.
* [ ] Redis key dùng dấu `:`.
* [ ] Environment variable dùng `UPPER_SNAKE_CASE`.
* [ ] Không có cross-service foreign key.
* [ ] Không có tên mơ hồ như `data`, `info`, `value`, `process`.
* [ ] Không đổi tên contract đã freeze mà chưa tăng version.

---

## 22. Quyết định cần chốt trước khi chuyển sang FROZEN

* [ ] Xác nhận public resource dùng `/api/concerts`, không dùng `/api/events`.
* [ ] Xác nhận tên canonical là `ticket-service`, không dùng song song `e-ticket-service`.
* [ ] Xác nhận tên canonical là `csv-ingestion-service`.
* [ ] Xác nhận toàn bộ contract mới dùng `concertId`.
* [ ] Xác nhận pagination dùng `items`, `page`, `size`, `total`, `totalPages`.
* [ ] Xác nhận event thời gian dùng `occurredAt`, không dùng `timestamp`.
* [ ] Xác nhận queue prefix là consumer service.
* [ ] Xác nhận UUID version dùng cho entity ID và public token.
