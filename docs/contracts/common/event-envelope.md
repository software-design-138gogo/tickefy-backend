---

title: Event Envelope
status: DRAFT
version: 1.0
owner: BE Lead
reviewers:
lastUpdated: 2026-06-16

---

# Event Envelope

## 1. Mục tiêu

Tài liệu này định nghĩa cấu trúc metadata chung cho mọi message/event được truyền giữa các backend service của Tickefy qua RabbitMQ.

Event Envelope giúp toàn hệ thống thống nhất cách:

* Xác định duy nhất một message.
* Nhận biết loại event và phiên bản contract.
* Xác định service phát event.
* Trace một luồng nghiệp vụ xuyên qua nhiều service.
* Xác định request hoặc message đã gây ra event hiện tại.
* Chống xử lý message trùng.
* Retry message khi consumer gặp lỗi tạm thời.
* Chuyển message lỗi sang Dead Letter Queue.
* Quản lý thay đổi event contract theo phiên bản.
* Điều tra lỗi trong hệ thống phân tán.

Event Envelope chỉ định nghĩa **metadata chung**. Dữ liệu nghiệp vụ riêng của từng event phải được đặt trong trường `payload` và được mô tả trong một Event Contract riêng.

```text
Event Envelope
├── messageId
├── eventType
├── eventVersion
├── source
├── occurredAt
├── correlationId
├── causationId
└── payload
    └── Dữ liệu nghiệp vụ riêng của từng event
```

---

## 2. Phạm vi áp dụng

Event Envelope này áp dụng cho tất cả integration event được publish lên RabbitMQ giữa các service của Tickefy.

Ví dụ:

```text
ConcertPublished
ConcertCancelled
PaymentSucceeded
PaymentFailed
OrderPaid
OrderPaymentFailed
OrderExpired
OrderCancelled
OrderRefunded
TicketsIssued
TicketRevoked
TicketCheckedIn
ConcertIntroductionGenerated
VipGuestImportCompleted
VipGuestImportFailed
```

Event Envelope không áp dụng trực tiếp cho:

* REST API request/response.
* Internal method call trong cùng service.
* Log entry.
* File PDF, CSV hoặc binary data.
* Background job nội bộ không truyền qua RabbitMQ.
* Command nội bộ chỉ được xử lý trong một service.

---

## 3. Envelope chuẩn

Mọi integration event phải sử dụng cấu trúc sau:

```json
{
  "messageId": "b71dd9eb-cae0-4c64-b8e5-3024bd69d6cc",
  "eventType": "OrderPaid",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T10:00:00Z",
  "correlationId": "req-4e514bee-7e61-417c-a564-f1d87aeccdb2",
  "causationId": "a9ed22c7-bb74-4410-845a-68130876bfe6",
  "payload": {
    "orderId": "f26e3025-b27b-4ff5-b924-b462ec717869"
  }
}
```

Nếu event không được tạo ra từ một message trước đó, `causationId` có thể là `null` hoặc được bỏ khỏi JSON:

```json
{
  "messageId": "3cb22396-a7d7-40a0-bcd7-a1ee205af73b",
  "eventType": "ConcertPublished",
  "eventVersion": "1.0",
  "source": "event-service",
  "occurredAt": "2026-06-16T10:00:00Z",
  "correlationId": "req-2c3e6700-0429-43e6-9df5-a06545fd6412",
  "causationId": null,
  "payload": {
    "concertId": "01f22e24-255c-4594-adb0-bc81af09f32f"
  }
}
```

---

## 4. Field definitions

| Field           | Type                    | Required | Mô tả                                          |
| --------------- | ----------------------- | -------: | ---------------------------------------------- |
| `messageId`     | UUID string             |      Yes | ID duy nhất của một message occurrence         |
| `eventType`     | String                  |      Yes | Tên loại domain/integration event              |
| `eventVersion`  | String                  |      Yes | Phiên bản schema của event payload             |
| `source`        | String                  |      Yes | Tên canonical của producer service             |
| `occurredAt`    | ISO-8601 UTC string     |      Yes | Thời điểm sự kiện nghiệp vụ xảy ra             |
| `correlationId` | String                  |      Yes | ID dùng để trace toàn bộ flow xuyên service    |
| `causationId`   | UUID string hoặc `null` |       No | ID của message trực tiếp gây ra event hiện tại |
| `payload`       | JSON object             |      Yes | Dữ liệu nghiệp vụ riêng của event              |

---

## 5. Quy định cho từng field

### 5.1. `messageId`

`messageId` là ID duy nhất của một lần phát message.

Quy ước:

```text
UUID v4
```

Ví dụ:

```json
{
  "messageId": "b71dd9eb-cae0-4c64-b8e5-3024bd69d6cc"
}
```

Mục đích chính:

* Chống xử lý message trùng.
* Theo dõi trạng thái xử lý message.
* Tìm message trong log.
* Phân biệt các event occurrence cùng loại.
* Hỗ trợ retry và DLQ replay.

Consumer phải dùng `messageId` làm idempotency key.

```text
Nếu messageId chưa được xử lý:
    thực hiện business logic
    ghi nhận messageId
    ACK message

Nếu messageId đã được xử lý:
    không chạy business logic lần nữa
    ACK message
```

Khi producer retry gửi lại **cùng một event nghiệp vụ** do chưa chắc lần publish trước đã thành công, producer phải giữ nguyên `messageId`.

Không dùng `eventId` để thay thế `messageId`, vì `eventId` có thể bị hiểu nhầm là ID của concert.

Quy ước phân biệt:

```text
messageId = ID của message RabbitMQ
concertId = ID của concert
eventType = tên loại integration event
```

---

### 5.2. `eventType`

`eventType` xác định sự kiện nghiệp vụ đã xảy ra.

Quy ước đặt tên:

```text
PascalCase
Được viết ở dạng sự kiện đã xảy ra
Không dùng tên mang ý nghĩa command
```

Ví dụ hợp lệ:

```text
ConcertPublished
ConcertCancelled
PaymentSucceeded
PaymentFailed
OrderPaid
OrderPaymentFailed
OrderExpired
OrderCancelled
OrderRefunded
TicketsIssued
TicketCheckedIn
ConcertIntroductionGenerated
VipGuestImportCompleted
VipGuestImportFailed
```

Ví dụ không hợp lệ:

```text
PublishConcert
ProcessPayment
CreateTicket
SendNotification
HandleOrder
```

Event phải diễn tả một sự kiện đã xảy ra, không phải yêu cầu một service thực hiện hành động.

```text
Command: IssueTickets
Event: TicketsIssued
```

`eventType` phải khớp chính xác với Event Contract đã được freeze.

---

### 5.3. `eventVersion`

`eventVersion` xác định phiên bản schema của event.

Quy ước:

```text
MAJOR.MINOR
```

Ví dụ:

```json
{
  "eventVersion": "1.0"
}
```

Ý nghĩa:

* `MAJOR`: tăng khi có breaking change.
* `MINOR`: tăng khi có thay đổi tương thích ngược cần được ghi nhận.

Ví dụ:

```text
1.0 → contract đầu tiên
1.1 → thêm optional field
2.0 → xóa field, đổi tên field hoặc đổi kiểu dữ liệu
```

Consumer phải xác định rõ phiên bản event mà mình hỗ trợ.

Khi consumer nhận một major version chưa được hỗ trợ:

1. Không chạy business logic.
2. Ghi structured log chứa `messageId`, `eventType` và `eventVersion`.
3. Không retry vô hạn vì đây không phải lỗi tạm thời.
4. Chuyển message sang DLQ.
5. Phát cảnh báo cho service owner.

---

### 5.4. `source`

`source` là tên canonical của service đã publish event.

Danh sách tên service chuẩn:

```text
auth-service
event-service
inventory-service
order-service
payment-service
notification-service
ticket-service
checkin-service
ai-bio-service
csv-ingestion-service
```

Không dùng:

* Tên thành viên.
* Tên class.
* Hostname.
* IP.
* Container instance.
* Tên có suffix scale instance.

Ví dụ không hợp lệ:

```text
payment-service-1
order-consumer-02
PaymentPublisher
duong-payment
10.0.0.15
```

Nếu `payment-service` chạy ba instance, tất cả event vẫn phải có:

```json
{
  "source": "payment-service"
}
```

Instance cụ thể có thể được lưu trong log dưới field riêng như `instanceId`, nhưng không được đưa vào `source`.

---

### 5.5. `occurredAt`

`occurredAt` là thời điểm sự kiện nghiệp vụ thực sự xảy ra.

Định dạng:

```text
ISO-8601 UTC
```

Ví dụ:

```json
{
  "occurredAt": "2026-06-16T10:00:00Z"
}
```

`occurredAt` không nhất thiết là thời điểm message được consumer nhận.

Ví dụ:

```text
10:00:00 → Payment được xác nhận thành công.
10:00:01 → Event được ghi vào outbox.
10:00:03 → Event được publish lên RabbitMQ.
10:00:05 → Order Service nhận event.
```

Trong trường hợp này:

```json
{
  "occurredAt": "2026-06-16T10:00:00Z"
}
```

Không dùng local time không có timezone:

```text
2026-06-16 17:00:00
```

---

### 5.6. `correlationId`

`correlationId` dùng để trace toàn bộ một business flow xuyên qua nhiều service.

Ví dụ luồng mua vé:

```text
Client request
→ API Gateway
→ Order Service
→ Inventory Service
→ Payment Service
→ PaymentSucceeded
→ OrderPaid
→ Ticket Service
→ Notification Service
```

Tất cả request, log và event trong flow này phải giữ cùng một `correlationId`.

Ví dụ:

```json
{
  "correlationId": "req-4e514bee-7e61-417c-a564-f1d87aeccdb2"
}
```

Quy tắc tạo và truyền:

1. API Gateway nhận `X-Request-ID` từ client nếu có.
2. Nếu client không gửi, Gateway hoặc service đầu tiên tự sinh.
3. Giá trị đó được dùng làm `correlationId`.
4. Service truyền nó sang downstream HTTP request.
5. Event phát sinh từ request phải giữ cùng `correlationId`.
6. Consumer tiếp tục propagate `correlationId` khi gọi service hoặc phát event mới.

Đối với background job không bắt đầu từ HTTP request:

* Service tạo một `correlationId` mới khi tạo job.
* Tất cả event của job sử dụng cùng `correlationId`.

---

### 5.7. `causationId`

`causationId` xác định message trực tiếp đã gây ra event hiện tại.

Ví dụ:

```text
PaymentSucceeded
    messageId = msg-payment-123

Order Service consume PaymentSucceeded
    ↓

OrderPaid
    causationId = msg-payment-123
```

Ví dụ JSON:

```json
{
  "messageId": "msg-order-paid-456",
  "eventType": "OrderPaid",
  "causationId": "msg-payment-123"
}
```

Phân biệt:

```text
correlationId
→ liên kết toàn bộ business flow

causationId
→ liên kết trực tiếp event cha với event con
```

Ví dụ chuỗi:

```text
PaymentSucceeded
    ↓ causationId
OrderPaid
    ↓ causationId
TicketsIssued
```

Cả ba event cùng có một `correlationId`, nhưng mỗi event con có `causationId` trỏ đến `messageId` của event ngay trước nó.

Khi event bắt đầu từ HTTP request và không có parent message, `causationId` có thể là `null`.

---

### 5.8. `payload`

`payload` chứa dữ liệu nghiệp vụ riêng của event.

Ví dụ:

```json
{
  "payload": {
    "orderId": "order-uuid",
    "userId": "user-uuid",
    "concertId": "concert-uuid",
    "paidAt": "2026-06-16T10:00:00Z"
  }
}
```

Quy tắc:

* `payload` phải là JSON object.
* Không để `payload` là `null`.
* Không đặt metadata envelope vào trong `payload`.
* Không gửi Java entity/database row trực tiếp.
* Chỉ gửi dữ liệu consumer thực sự cần.
* Field phải được định nghĩa trong Event Contract.
* ID cross-service dùng UUID string.
* Thời gian dùng ISO-8601 UTC.
* Tiền dùng integer VND.
* Enum dùng `UPPER_SNAKE_CASE`.

Không nên:

```json
{
  "payload": {
    "orderEntity": {
      "...": "toàn bộ database entity"
    }
  }
}
```

Nên:

```json
{
  "payload": {
    "orderId": "order-uuid",
    "userId": "user-uuid",
    "concertId": "concert-uuid",
    "items": [
      {
        "orderItemId": "order-item-uuid",
        "ticketTypeId": "ticket-type-uuid",
        "quantity": 2,
        "unitPrice": 1500000
      }
    ],
    "totalAmount": 3000000,
    "paidAt": "2026-06-16T10:00:00Z"
  }
}
```

---

## 6. RabbitMQ convention

### 6.1. Exchange

Integration event của Tickefy dùng topic exchange:

```text
tickefy.events
```

Cấu hình đề xuất:

| Thuộc tính  | Giá trị |
| ----------- | ------- |
| Type        | `topic` |
| Durable     | `true`  |
| Auto-delete | `false` |

---

### 6.2. Routing key

Routing key sử dụng:

```text
lowercase.dot.separated
```

Ví dụ:

| Event type                | Routing key                  |
| ------------------------- | ---------------------------- |
| `ConcertPublished`        | `concert.published`          |
| `ConcertCancelled`        | `concert.cancelled`          |
| `PaymentSucceeded`        | `payment.succeeded`          |
| `PaymentFailed`           | `payment.failed`             |
| `OrderPaid`               | `order.paid`                 |
| `OrderPaymentFailed`      | `order.payment.failed`       |
| `OrderExpired`            | `order.expired`              |
| `OrderCancelled`          | `order.cancelled`            |
| `OrderRefunded`           | `order.refunded`             |
| `TicketsIssued`           | `tickets.issued`             |
| `TicketCheckedIn`         | `ticket.checked-in`          |
| `ConcertIntroductionGenerated` | `concert.introduction.generated` |
| `VipGuestImportCompleted` | `vip-guest-import.completed` |
| `VipGuestImportFailed`    | `vip-guest-import.failed`    |

Routing key không được sử dụng làm contract thay thế cho `eventType`. Consumer phải kiểm tra cả routing key và `eventType`.

---

### 6.3. Queue naming

Mỗi service có một queue riêng cho mỗi event hoặc nhóm event mà service consume.

Quy ước:

```text
{consumer-service}.{event-name}
```

Ví dụ:

```text
order.payment-succeeded
order.payment-failed
inventory.order-paid
inventory.order-payment-failed
inventory.order-expired
inventory.order-cancelled
inventory.concert-published
inventory.concert-cancelled
order.concert-cancelled
ticket.order-paid
ticket.order-refunded
ticket.concert-cancelled
notification.tickets-issued
notification.order-refunded
notification.concert-cancelled
event.concert-introduction-generated
checkin.vip-guest-import-completed
```

Không cho nhiều loại service khác nhau dùng chung một queue.

Sai:

```text
order-paid-queue
```

Nếu Ticket Service và Notification Service cùng consume queue này, một message chỉ đến một trong hai service.

Đúng:

```text
ticket.order-paid
notification.order-paid
inventory.order-paid
```

Mỗi queue nhận một bản sao của event từ topic exchange.

Các instance thuộc cùng một service dùng chung queue để hoạt động theo competing consumer.

```text
ticket-service instance 1 ─┐
ticket-service instance 2 ─┼→ queue ticket.order-paid
ticket-service instance 3 ─┘
```

Mỗi message trong queue chỉ được một instance xử lý.

---

### 6.4. Dead Letter Queue

Mỗi consumer queue phải có DLQ.

Quy ước:

```text
{consumer-queue}.dlq
```

Ví dụ:

```text
ticket.order-paid.dlq
notification.tickets-issued.dlq
event.concert-introduction-generated.dlq
```

DLQ phải giữ nguyên:

* Message body.
* `messageId`.
* `correlationId`.
* `eventType`.
* `eventVersion`.
* RabbitMQ headers liên quan.
* Thông tin số lần retry nếu có.

---

### 6.5. Message persistence

Integration event quan trọng phải được publish dưới dạng persistent message.

```text
deliveryMode = 2
```

Queue và exchange phải được cấu hình durable.

Persistent message giảm rủi ro mất event khi RabbitMQ restart, nhưng không thay thế Outbox Pattern.

---

## 7. Producer rules

Mọi producer phải tuân theo các quy tắc sau.

### 7.1. Chỉ publish event sau khi nghiệp vụ thành công

Producer không được publish một event mô tả kết quả thành công trước khi business transaction thực sự commit.

Sai:

```text
Publish OrderPaid
→ Sau đó mới update Order thành PAID
```

Đúng:

```text
Update Order thành PAID
→ Commit transaction
→ Publish OrderPaid
```

Nếu update database thất bại, event không được publish.

---

### 7.2. Đảm bảo consistency bằng Outbox Pattern

Đối với event quan trọng, producer nên sử dụng Transactional Outbox Pattern.

Trong cùng một database transaction:

```text
1. Update business entity.
2. Insert outbox event.
3. Commit transaction.
```

Sau đó outbox publisher:

```text
1. Đọc event chưa publish.
2. Publish lên RabbitMQ.
3. Đánh dấu event đã publish.
```

Ví dụ:

```text
Payment callback thành công
→ Update payment_transactions.status = SUCCESS
→ Insert outbox PaymentSucceeded
→ Commit

Outbox worker
→ Publish PaymentSucceeded
→ Mark PUBLISHED
```

Outbox Pattern tránh hai lỗi:

```text
Database commit thành công nhưng publish event thất bại.
Publish event thành công nhưng database transaction rollback.
```

---

### 7.3. Producer phải propagate tracing metadata

Khi tạo event từ HTTP request:

```text
correlationId = requestId của request hiện tại
causationId = null hoặc message cha nếu request bắt nguồn từ consumer
```

Khi tạo event từ một consumed event:

```text
correlationId = correlationId của event cha
causationId = messageId của event cha
```

---

### 7.4. Producer không đưa dữ liệu lớn vào payload

Không gửi trực tiếp:

* PDF.
* CSV.
* SVG.
* QR image.
* Base64 file.
* Large extracted text.
* Full import error report.

Thay vào đó gửi object key hoặc URL nội bộ:

```json
{
  "payload": {
    "jobId": "job-uuid",
    "objectKey": "ai-bio/jobs/job-uuid/input.pdf"
  }
}
```

---

### 7.5. Producer không đưa dữ liệu nhạy cảm vào event

Không đưa vào payload:

* Password.
* Password hash.
* JWT.
* Refresh token.
* API key.
* Payment secret.
* Payment callback signature.
* Full card/bank account data.
* Full QR token nếu consumer không thực sự cần.
* Private object storage credential.

Thông tin cần log phải được mask.

---

### 7.6. Producer phải validate event trước khi publish

Producer phải kiểm tra:

* Envelope đủ field bắt buộc.
* Payload đúng Event Contract.
* `eventType` đúng.
* `eventVersion` đúng.
* `occurredAt` là UTC.
* Không có dữ liệu nhạy cảm.
* Routing key đúng convention.

---

## 8. Consumer rules

### 8.1. Consumer phải idempotent

RabbitMQ sử dụng cơ chế at-least-once delivery. Một message có thể được delivery nhiều lần.

Consumer không được giả định rằng mỗi event chỉ được nhận đúng một lần.

Consumer phải lưu `messageId` đã xử lý, ví dụ:

```sql
CREATE TABLE processed_messages (
    message_id   UUID PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Quy trình khuyến nghị:

```text
BEGIN TRANSACTION

1. Kiểm tra messageId trong processed_messages.
2. Nếu đã tồn tại:
   - COMMIT
   - ACK
   - Không xử lý business logic.
3. Nếu chưa tồn tại:
   - Thực hiện business logic.
   - Insert processed_messages.
   - COMMIT
   - ACK.

Nếu transaction lỗi:
   - ROLLBACK
   - Không ACK.
```

Việc ghi `processed_messages` và business change nên nằm trong cùng transaction khi có thể.

---

### 8.2. Chỉ ACK sau khi xử lý thành công

Consumer sử dụng manual acknowledgment.

Chỉ ACK khi:

* Payload hợp lệ.
* Business transaction thành công.
* Dữ liệu đã được commit.
* `messageId` đã được ghi nhận.
* Các side effect bắt buộc đã hoàn thành.

Không ACK trước khi database transaction commit.

---

### 8.3. Duplicate message phải được ACK

Nếu consumer phát hiện `messageId` đã được xử lý:

* Không chạy business logic lần nữa.
* Ghi debug/info log.
* ACK message.

Không reject duplicate message vào DLQ vì duplicate delivery là hành vi có thể xảy ra bình thường.

---

### 8.4. Phân loại lỗi

Consumer phải phân biệt lỗi retryable và non-retryable.

#### Retryable error

Ví dụ:

* Database tạm thời unavailable.
* Redis timeout.
* HTTP dependency timeout.
* Network error.
* Temporary object storage error.
* Lock timeout.
* RabbitMQ connection interrupted.

Hành vi:

```text
Retry có giới hạn
→ Exponential backoff
→ Quá số lần retry thì chuyển DLQ
```

#### Non-retryable error

Ví dụ:

* Payload thiếu required field.
* Event version không được hỗ trợ.
* UUID sai format.
* Enum không hợp lệ.
* Business reference vĩnh viễn không hợp lệ.
* Event contract bị sai.

Hành vi:

```text
Không retry nhiều lần
→ Log lỗi contract
→ Chuyển DLQ
→ Alert producer/consumer owner
```

---

### 8.5. Consumer không được thay đổi dữ liệu của service khác

Consumer chỉ được cập nhật database/schema mà service của nó sở hữu.

Ví dụ:

```text
Ticket Service consume OrderPaid
→ tạo ticket trong ticket_schema
→ không update trực tiếp order_schema
```

```text
Inventory Service consume OrderExpired/OrderCancelled
→ release reservation trong inventory_schema
→ không update trực tiếp order_schema
```

---

### 8.6. Consumer phải kiểm tra version

Trước khi xử lý:

```text
eventType có được hỗ trợ không?
eventVersion có được hỗ trợ không?
payload có đúng schema không?
```

Nếu không tương thích:

* Không chạy business logic.
* Chuyển DLQ.
* Log rõ version nhận được và version hỗ trợ.

---

## 9. Retry policy

Retry policy có thể khác nhau theo service, nhưng phải tuân theo nguyên tắc chung.

Cấu hình khuyến nghị:

| Lần retry | Delay đề xuất |
| --------: | ------------: |
|         1 |        5 giây |
|         2 |       30 giây |
|         3 |        2 phút |
|         4 |       10 phút |
|         5 |    Chuyển DLQ |

Không retry vô hạn.

Retry phải giữ nguyên:

* `messageId`.
* `eventType`.
* `eventVersion`.
* `correlationId`.
* `causationId`.
* `payload`.

Có thể thêm RabbitMQ headers để theo dõi:

```text
x-retry-count
x-first-failure-at
x-last-failure-reason
```

Không thay đổi envelope chỉ để tăng retry count.

---

## 10. DLQ handling

Message được chuyển DLQ khi:

* Vượt quá số lần retry.
* Event version không hỗ trợ.
* Payload không đúng schema.
* Consumer gặp lỗi non-retryable.
* Business invariant bị vi phạm và không thể tự phục hồi.
* Message không thể deserialize.

Mỗi DLQ phải có:

* Monitoring.
* Alert.
* Người chịu trách nhiệm.
* Quy trình xem lỗi.
* Quy trình sửa dữ liệu hoặc sửa consumer.
* Quy trình replay.

Không replay DLQ trước khi xác định và sửa nguyên nhân.

Khi replay:

* Giữ nguyên `messageId`.
* Giữ nguyên `correlationId`.
* Không chỉnh sửa payload trực tiếp trên production queue.
* Nếu bắt buộc sửa payload, phải tạo message mới với `messageId` mới và lưu audit record.

---

## 11. Event contract compatibility

### 11.1. Non-breaking changes

Các thay đổi sau được xem là tương thích ngược:

* Thêm optional field.
* Bổ sung enum value nếu consumer đã được thiết kế để xử lý unknown value.
* Mở rộng metadata không bắt buộc.
* Làm validation bớt nghiêm ngặt.

Ví dụ:

Phiên bản `1.0`:

```json
{
  "orderId": "uuid",
  "amount": 3000000
}
```

Phiên bản `1.1`:

```json
{
  "orderId": "uuid",
  "amount": 3000000,
  "currency": "VND"
}
```

Nếu `currency` là optional hoặc có giá trị mặc định, đây có thể là non-breaking change.

---

### 11.2. Breaking changes

Các thay đổi sau là breaking change:

* Xóa field.
* Đổi tên field.
* Đổi kiểu dữ liệu.
* Chuyển required field thành cấu trúc khác.
* Thay đổi ý nghĩa của field.
* Thay đổi đơn vị.
* Thay đổi enum theo cách consumer cũ không hiểu.
* Thay đổi cấu trúc nested object.
* Chuyển một giá trị từ integer sang decimal/string.

Ví dụ breaking:

```text
amount: integer VND
```

thành:

```text
amount: decimal USD
```

Breaking change phải tăng major version:

```text
1.0 → 2.0
```

---

### 11.3. Không thay đổi ý nghĩa field mà giữ nguyên tên

Không được giữ nguyên field nhưng thay đổi ý nghĩa.

Sai:

```text
Phiên bản cũ:
occurredAt = thời điểm payment thành công

Phiên bản mới:
occurredAt = thời điểm publish message
```

Trường hợp này phải tạo field mới hoặc tăng major version.

---

### 11.4. Consumer-first deployment

Khi triển khai thay đổi event contract:

1. Cập nhật consumer để hỗ trợ cả version cũ và version mới.
2. Deploy consumer.
3. Cập nhật producer phát version mới.
4. Theo dõi traffic và DLQ.
5. Sau thời gian tương thích, loại bỏ version cũ.

Không deploy producer có breaking change trước khi consumer sẵn sàng.

---

## 12. Logging và observability

Mỗi lần publish hoặc consume event phải log ít nhất:

```text
messageId
eventType
eventVersion
source
correlationId
causationId
routingKey
queue
consumerService
processingResult
processingDuration
retryCount
```

Ví dụ structured log:

```json
{
  "level": "INFO",
  "service": "ticket-service",
  "action": "EVENT_CONSUMED",
  "messageId": "b71dd9eb-cae0-4c64-b8e5-3024bd69d6cc",
  "eventType": "OrderPaid",
  "eventVersion": "1.0",
  "correlationId": "req-4e514bee-7e61-417c-a564-f1d87aeccdb2",
  "queue": "ticket.order-paid",
  "result": "SUCCESS",
  "durationMs": 128
}
```

Không log toàn bộ payload nếu payload chứa dữ liệu cá nhân hoặc dữ liệu nhạy cảm.

Các metric khuyến nghị:

```text
events_published_total
events_consumed_total
events_processed_success_total
events_processed_failed_total
events_duplicate_total
events_retry_total
events_dlq_total
event_processing_duration_ms
```

---

## 13. Security rules

Event không được chứa:

* Password hoặc password hash.
* JWT hoặc refresh token.
* Secret key.
* AI provider API key.
* Payment provider secret.
* Full payment signature.
* Full bank/card data.
* Full QR token nếu không bắt buộc.
* Credential của Object Storage.
* Stack trace.

Thông tin cá nhân chỉ được gửi khi consumer thực sự cần.

Ưu tiên gửi ID để consumer truy xuất dữ liệu qua API hoặc projection đã được cấp quyền, thay vì gửi toàn bộ dữ liệu cá nhân.

Ví dụ nên tránh:

```json
{
  "payload": {
    "email": "user@example.com",
    "phone": "0900000000",
    "address": "..."
  }
}
```

Trừ khi event dành cho Notification Service và các field này đã được xác định rõ trong contract và chính sách bảo mật.

---

## 14. Ví dụ event trong Tickefy

### 14.1. `PaymentSucceeded`

```json
{
  "messageId": "c736ec21-a1b6-4553-9ccd-c96f6e952e9e",
  "eventType": "PaymentSucceeded",
  "eventVersion": "1.0",
  "source": "payment-service",
  "occurredAt": "2026-06-16T10:00:00Z",
  "correlationId": "req-6f3fefcc-37b4-4419-ae0b-4bd735fd4e87",
  "causationId": null,
  "payload": {
    "paymentId": "2f64ac92-dffc-463a-9868-17f8f051f687",
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "amount": 3000000,
    "currency": "VND",
    "provider": "MOCK",
    "providerTransactionId": "MOCK-20260616-00001",
    "paidAt": "2026-06-16T10:00:00Z"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: payment.succeeded
Queue: order.payment-succeeded
```

---

### 14.1.1. `PaymentFailed`

```json
{
  "messageId": "eb7c0d3f-35df-459f-8c0b-138a17f6467d",
  "eventType": "PaymentFailed",
  "eventVersion": "1.0",
  "source": "payment-service",
  "occurredAt": "2026-06-16T10:15:00Z",
  "correlationId": "req-6f3fefcc-37b4-4419-ae0b-4bd735fd4e87",
  "causationId": null,
  "payload": {
    "paymentId": "2f64ac92-dffc-463a-9868-17f8f051f687",
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "amount": 3000000,
    "currency": "VND",
    "provider": "MOCK",
    "providerTransactionId": "MOCK-20260616-00001",
    "failedAt": "2026-06-16T10:15:00Z",
    "reason": "PAYMENT_TIMEOUT"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: payment.failed
Queue: order.payment-failed
```

---

### 14.2. `OrderPaid`

```json
{
  "messageId": "7363826e-5407-465d-b8f7-d4b2125045f4",
  "eventType": "OrderPaid",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T10:00:02Z",
  "correlationId": "req-6f3fefcc-37b4-4419-ae0b-4bd735fd4e87",
  "causationId": "c736ec21-a1b6-4553-9ccd-c96f6e952e9e",
  "payload": {
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "userId": "508020c5-d766-4d3d-baf9-e0bb405698ad",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "reservationId": "786214c3-0dc1-4e42-82f4-40dfbf7da98f",
    "items": [
      {
        "orderItemId": "d22c6016-740f-485c-815b-ac69cb375aae",
        "ticketTypeId": "ed979f53-b9a4-4597-a9be-29171c99c8d0",
        "ticketTypeName": "SVIP",
        "quantity": 2,
        "unitPrice": 1500000
      }
    ],
    "totalAmount": 3000000,
    "paidAt": "2026-06-16T10:00:00Z"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: order.paid

Queues:
- inventory.order-paid
- ticket.order-paid
- notification.order-paid
```

---

### 14.2.1. `OrderPaymentFailed`

```json
{
  "messageId": "86cb6f3f-306f-4478-9f11-c9b9c42e9721",
  "eventType": "OrderPaymentFailed",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T10:15:00Z",
  "correlationId": "req-6f3fefcc-37b4-4419-ae0b-4bd735fd4e87",
  "causationId": null,
  "payload": {
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "userId": "508020c5-d766-4d3d-baf9-e0bb405698ad",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "reservationId": "786214c3-0dc1-4e42-82f4-40dfbf7da98f",
    "failedAt": "2026-06-16T10:15:00Z",
    "reason": "PAYMENT_FAILED"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: order.payment.failed
Queues:
- inventory.order-payment-failed
- notification.order-payment-failed
```

---

### 14.2.2. `OrderExpired`

```json
{
  "messageId": "3d2bfa0b-8396-4964-9677-77e5a367bc26",
  "eventType": "OrderExpired",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T10:15:00Z",
  "correlationId": "req-6f3fefcc-37b4-4419-ae0b-4bd735fd4e87",
  "causationId": null,
  "payload": {
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "userId": "508020c5-d766-4d3d-baf9-e0bb405698ad",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "reservationId": "786214c3-0dc1-4e42-82f4-40dfbf7da98f",
    "expiredAt": "2026-06-16T10:15:00Z"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: order.expired
Queues:
- inventory.order-expired
- notification.order-expired
```

---

### 14.2.3. `OrderCancelled`

```json
{
  "messageId": "8fa441c6-2917-4707-95f8-a19e352e7a5e",
  "eventType": "OrderCancelled",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T10:08:00Z",
  "correlationId": "req-6f3fefcc-37b4-4419-ae0b-4bd735fd4e87",
  "causationId": null,
  "payload": {
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "userId": "508020c5-d766-4d3d-baf9-e0bb405698ad",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "reservationId": "786214c3-0dc1-4e42-82f4-40dfbf7da98f",
    "cancelledAt": "2026-06-16T10:08:00Z",
    "reason": "USER_CANCELLED"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: order.cancelled
Queues:
- inventory.order-cancelled
- notification.order-cancelled
```

---

### 14.2.4. `OrderRefunded`

```json
{
  "messageId": "fb7fa2c9-c402-46bb-a15f-949bf1654d3a",
  "eventType": "OrderRefunded",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T11:00:00Z",
  "correlationId": "req-76453caa-87bb-4501-b3fd-d220dd26da6e",
  "causationId": "concert-cancelled-message-id",
  "payload": {
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "userId": "508020c5-d766-4d3d-baf9-e0bb405698ad",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "paymentId": "2f64ac92-dffc-463a-9868-17f8f051f687",
    "refundAmount": 3000000,
    "currency": "VND",
    "refundedAt": "2026-06-16T11:00:00Z",
    "reason": "CONCERT_CANCELLED"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: order.refunded
Queues:
- ticket.order-refunded
- notification.order-refunded
```

---

### 14.3. `TicketsIssued`

```json
{
  "messageId": "f79b375c-dab8-4247-b026-ceef59396eb2",
  "eventType": "TicketsIssued",
  "eventVersion": "1.0",
  "source": "ticket-service",
  "occurredAt": "2026-06-16T10:00:04Z",
  "correlationId": "req-6f3fefcc-37b4-4419-ae0b-4bd735fd4e87",
  "causationId": "7363826e-5407-465d-b8f7-d4b2125045f4",
  "payload": {
    "orderId": "1359224e-6155-4f03-b424-d81ab81ead47",
    "userId": "508020c5-d766-4d3d-baf9-e0bb405698ad",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "tickets": [
      {
        "ticketId": "d4c03fb6-67d8-4911-88c4-08eb3bcf99cc",
        "orderItemId": "d22c6016-740f-485c-815b-ac69cb375aae",
        "ticketTypeId": "ed979f53-b9a4-4597-a9be-29171c99c8d0",
        "ticketTypeName": "SVIP",
        "status": "ISSUED"
      },
      {
        "ticketId": "887a41c5-b5a5-40ac-afd6-21ffd24fd4d9",
        "orderItemId": "d22c6016-740f-485c-815b-ac69cb375aae",
        "ticketTypeId": "ed979f53-b9a4-4597-a9be-29171c99c8d0",
        "ticketTypeName": "SVIP",
        "status": "ISSUED"
      }
    ],
    "issuedAt": "2026-06-16T10:00:04Z"
  }
}
```

Không đưa full QR token vào event này nếu Notification Service có thể lấy QR qua API nội bộ an toàn.

Routing:

```text
Exchange: tickefy.events
Routing key: tickets.issued
Queue: notification.tickets-issued
```

---

### 14.4. `ConcertPublished`

```json
{
  "messageId": "df210dfd-9d64-4d86-b22d-6c0b963b0d99",
  "eventType": "ConcertPublished",
  "eventVersion": "1.0",
  "source": "event-service",
  "occurredAt": "2026-06-16T09:00:00Z",
  "correlationId": "req-76453caa-87bb-4501-b3fd-d220dd26da6e",
  "causationId": null,
  "payload": {
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "organizerId": "organizer-user-uuid",
    "publishedAt": "2026-06-16T09:00:00Z",
    "startsAt": "2026-06-20T12:00:00Z",
    "endsAt": "2026-06-20T15:00:00Z"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: concert.published
Queues:
- inventory.concert-published
```

---

### 14.5. `ConcertCancelled`

```json
{
  "messageId": "8a8d6d6d-15bb-4db8-9565-a686c4f7cb95",
  "eventType": "ConcertCancelled",
  "eventVersion": "1.0",
  "source": "event-service",
  "occurredAt": "2026-06-16T13:00:00Z",
  "correlationId": "req-8a3d3e99-455a-4920-b18f-08975f33c651",
  "causationId": null,
  "payload": {
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "cancelledAt": "2026-06-16T13:00:00Z",
    "reason": "Organizer cancelled the concert."
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: concert.cancelled
Queues:
- order.concert-cancelled
- inventory.concert-cancelled
- ticket.concert-cancelled
- notification.concert-cancelled
```

---

### 14.6. `TicketCheckedIn` (optional)

> Event này chỉ publish nếu có consumer cần realtime analytics/notification. Audit chính vẫn nằm trong `checkin-service` database.

```json
{
  "messageId": "eced8908-3339-47dd-933c-9b1c08d138dd",
  "eventType": "TicketCheckedIn",
  "eventVersion": "1.0",
  "source": "ticket-service",
  "occurredAt": "2026-06-16T10:10:00Z",
  "correlationId": "req-1ce71050-13ba-4405-8a5e-dbd772785d3d",
  "causationId": null,
  "payload": {
    "ticketId": "d4c03fb6-67d8-4911-88c4-08eb3bcf99cc",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "userId": "508020c5-d766-4d3d-baf9-e0bb405698ad",
    "staffId": "staff-uuid",
    "gate": "GATE_A",
    "checkedInAt": "2026-06-16T10:10:00Z"
  }
}
```

Payload không chứa raw `qrToken`.

Routing:

```text
Exchange: tickefy.events
Routing key: ticket.checked-in
Queue: analytics.ticket-checked-in   # optional / future
```

---

### 14.7. `ConcertIntroductionGenerated`

```json
{
  "messageId": "bc6e68bf-bbeb-41c0-a186-cbd1cfa237a1",
  "eventType": "ConcertIntroductionGenerated",
  "eventVersion": "1.0",
  "source": "ai-bio-service",
  "occurredAt": "2026-06-16T11:00:00Z",
  "correlationId": "job-correlation-62d2e33a",
  "causationId": null,
  "payload": {
    "jobId": "d50a733b-e47b-499a-aee2-d30b7a34b39a",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "introduction": "Nội dung giới thiệu concert đã được AI tạo.",
    "language": "vi",
    "sourceDocumentIds": [
      "source-document-uuid"
    ],
    "requestedAt": "2026-06-16T10:55:00Z",
    "generatedAt": "2026-06-16T11:00:00Z"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: concert.introduction.generated
Queue: event.concert-introduction-generated
```

---

### 14.8. `VipGuestImportCompleted`

```json
{
  "messageId": "257f1670-703d-44aa-b7a3-93fd2609d913",
  "eventType": "VipGuestImportCompleted",
  "eventVersion": "1.0",
  "source": "csv-ingestion-service",
  "occurredAt": "2026-06-16T12:00:00Z",
  "correlationId": "job-correlation-f6b1f799",
  "causationId": null,
  "payload": {
    "importJobId": "90ee94cf-ab2a-4c55-9618-b8c18336ac7c",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "totalRows": 100,
    "successRows": 95,
    "failedRows": 3,
    "duplicateRows": 2,
    "errorReportObjectKey": "vip-import/jobs/90ee94cf/errors.csv",
    "completedAt": "2026-06-16T12:00:00Z"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: vip-guest-import.completed
Queue: checkin.vip-guest-import-completed
```

### 14.9. `VipGuestImportFailed`

```json
{
  "messageId": "0dfd9169-33e0-4c5f-9c2f-6ce6109b9cb6",
  "eventType": "VipGuestImportFailed",
  "eventVersion": "1.0",
  "source": "csv-ingestion-service",
  "occurredAt": "2026-06-16T12:05:00Z",
  "correlationId": "job-correlation-f6b1f799",
  "causationId": null,
  "payload": {
    "importJobId": "90ee94cf-ab2a-4c55-9618-b8c18336ac7c",
    "concertId": "54062b96-3fbf-421f-b42c-c8fba5542a18",
    "totalRows": 100,
    "successRows": 0,
    "failedRows": 60,
    "duplicateRows": 0,
    "failureReason": "ERROR_THRESHOLD_EXCEEDED",
    "errorReportObjectKey": "vip-import/jobs/90ee94cf/errors.csv",
    "failedAt": "2026-06-16T12:05:00Z"
  }
}
```

Routing:

```text
Exchange: tickefy.events
Routing key: vip-guest-import.failed
Queue: monitoring.vip-guest-import-failed
```

---

## 15. Validation checklist

Trước khi freeze hoặc publish một Event Contract, kiểm tra:

### Envelope

* [ ] Có `messageId`.
* [ ] `messageId` là UUID hợp lệ.
* [ ] Có `eventType`.
* [ ] `eventType` dùng PascalCase và diễn tả sự kiện đã xảy ra.
* [ ] Có `eventVersion`.
* [ ] Có `source` đúng tên service canonical.
* [ ] `occurredAt` là ISO-8601 UTC.
* [ ] Có `correlationId`.
* [ ] `causationId` đúng quan hệ event cha nếu có.
* [ ] `payload` là JSON object.

### Payload

* [ ] Payload có Event Contract riêng.
* [ ] Không expose database entity trực tiếp.
* [ ] Không có file lớn.
* [ ] Không có secret hoặc token nhạy cảm.
* [ ] ID dùng UUID string.
* [ ] Time dùng UTC.
* [ ] Money dùng integer VND.
* [ ] Enum dùng `UPPER_SNAKE_CASE`.

### Producer

* [ ] Event chỉ được tạo sau khi nghiệp vụ thành công.
* [ ] Có Outbox Pattern nếu event quan trọng.
* [ ] Publish persistent message.
* [ ] Routing key đúng convention.
* [ ] Correlation metadata được propagate.

### Consumer

* [ ] Consumer idempotent theo `messageId`.
* [ ] Chỉ ACK sau khi transaction commit.
* [ ] Duplicate message được ACK an toàn.
* [ ] Retry có giới hạn.
* [ ] Có DLQ.
* [ ] Có monitoring và alert.
* [ ] Kiểm tra `eventVersion`.
* [ ] Không query hoặc update trực tiếp database service khác.

---

## 16. Definition of Done

Event Envelope được xem là áp dụng hoàn chỉnh khi:

* [ ] Tất cả producer sử dụng cùng cấu trúc envelope.
* [ ] Tất cả consumer deserialize cùng envelope model.
* [ ] Tất cả consumer xử lý idempotent theo `messageId`.
* [ ] `correlationId` được truyền xuyên HTTP và RabbitMQ.
* [ ] Mỗi queue có retry policy và DLQ.
* [ ] RabbitMQ exchange, queue và routing key được khai báo trong infrastructure repo.
* [ ] Mỗi event có Event Contract riêng.
* [ ] Contract tests kiểm tra envelope và payload schema.
* [ ] Logs tìm kiếm được theo `messageId` và `correlationId`.
* [ ] Không có dữ liệu nhạy cảm trong message.
* [ ] Quy trình replay DLQ được document.

---

## 17. Open decisions

Các nội dung sau phải được team review trước khi chuyển tài liệu sang `FROZEN`:

* [ ] Có bắt buộc triển khai Outbox Pattern cho toàn bộ event hay chỉ các event critical?
* [ ] Số lần retry mặc định cho mỗi queue.
* [ ] Cách triển khai delayed retry trong RabbitMQ.
* [ ] Thời gian lưu `processed_messages`.
* [ ] Thời gian lưu outbox event.
* [ ] Có sử dụng JSON Schema để validate event contract tự động hay không?
* [ ] Công cụ monitoring và alert cho DLQ.
* [ ] Quy trình và quyền thực hiện DLQ replay.
* [ ] Có thêm `tenantId`, `traceId` hoặc `schemaUrl` trong phiên bản sau hay không?
