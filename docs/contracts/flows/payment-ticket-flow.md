---
title: Flow Contract - Payment to Ticket Issuing
status: DRAFT
version: 1.1
owner: Hòa
reviewers: [BE Lead, Payment, Order, Notification]
lastUpdated: 2026-06-18
---

# Flow Contract — Payment → Ticket Issuing

## 1. Mục tiêu

Flow này mô tả cách một đơn hàng đã thanh toán tạo ra ticket điện tử.

Kết quả cuối cùng mong muốn:

- Payment callback được xử lý idempotent.
- `order-service` chuyển order sang `PAID` và publish `OrderPaid` qua outbox.
- `inventory-service` consume `OrderPaid` để commit reservation; `order-service` không gọi Inventory commit sync.
- `ticket-service` consume `OrderPaid`, issue tickets, publish `TicketsIssued`.
- `notification-service` consume `OrderPaid` để báo thanh toán thành công và consume `TicketsIssued` để gửi ticket link.

## 2. Participants

| Participant | Responsibility |
|---|---|
| Payment Gateway | Gửi callback payment status |
| `payment-service` | Verify callback, dedup gateway transaction, publish payment success |
| `order-service` | Mark order paid, write/publish `OrderPaid` |
| `inventory-service` | Consume `OrderPaid` and commit reservation |
| RabbitMQ | Durable async event delivery |
| `ticket-service` | Issue tickets idempotently |
| `notification-service` | Notify audience on payment success and ticket issued |
| PostgreSQL | Source of truth per service schema |

## 3. Preconditions

- Order đã tồn tại và còn payable.
- Inventory reservation vẫn hợp lệ tại thời điểm payment success.
- Payment callback có signature hợp lệ.
- RabbitMQ exchange/queues/DLQ đã configured.
- `ticket-service` consumer đang chạy hoặc queue durable để nhận sau.

## 4. Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Gateway as Payment Gateway
    participant Payment as payment-service
    participant Order as order-service
    participant MQ as RabbitMQ
    participant Inventory as inventory-service
    participant Ticket as ticket-service
    participant Notification as notification-service

    Gateway->>Payment: POST payment callback
    Payment->>Payment: verify signature and idempotency
    Payment->>Payment: mark payment SUCCESS and write outbox
    Payment->>MQ: publish PaymentSucceeded
    Payment-->>Gateway: HTTP 200 callback accepted

    MQ-->>Order: deliver PaymentSucceeded
    Order->>Order: validate order and mark PAID
    Order->>Order: write OrderPaid outbox
    Order->>MQ: publish OrderPaid

    MQ-->>Inventory: deliver OrderPaid
    Inventory->>Inventory: commit reservation idempotently
    MQ-->>Notification: deliver OrderPaid
    Notification->>Notification: notify payment success

    MQ-->>Ticket: deliver OrderPaid
    Ticket->>Ticket: dedup messageId and orderItemId plus seatSequence
    Ticket->>Ticket: create tickets and QR metadata
    Ticket->>MQ: publish TicketsIssued
    MQ-->>Notification: deliver TicketsIssued
    Notification->>Notification: send ticket link by email or push
```

## 5. Event contracts

| Event | Producer | Consumer | Routing key | Contract |
|---|---|---|---|---|
| `PaymentSucceeded` | `payment-service` | `order-service` (`order.payment-succeeded`) | `payment.succeeded` | `../common/event-envelope.md` §14.1 |
| `OrderPaid` | `order-service` | `inventory-service` (`inventory.order-paid`), `ticket-service` (`ticket.order-paid`), `notification-service` (`notification.order-paid`) | `order.paid` | `../common/event-envelope.md` §14.2 |
| `TicketsIssued` | `ticket-service` | `notification-service` (`notification.tickets-issued`) | `tickets.issued` | `../common/event-envelope.md` §14.3 |

Contract requirement:

- Payload uses `concertId`, not `eventId`.
- Ticket display field uses `ticketTypeName`, not `ticketName` or `zoneName`.
- No event contains raw `qrToken`.
- Inventory commit happens by consuming `OrderPaid`; `order-service` must not call Inventory commit/release through sync HTTP in this path.
- `notification-service` must listen to Order/Ticket events, not `PaymentSucceeded` directly.

## 6. State transitions

### Order state

```mermaid
stateDiagram-v2
    [*] --> PAYMENT_PENDING
    PAYMENT_PENDING --> PAID: PaymentSucceeded consumed
    PAYMENT_PENDING --> PAYMENT_FAILED: PaymentFailed consumed
    PAYMENT_PENDING --> EXPIRED: expire worker
    PAID --> [*]
    PAYMENT_FAILED --> [*]
    EXPIRED --> [*]
```

> `TICKET_PENDING` / `TICKET_ISSUED` không phải order state bắt buộc trong MVP. Ticket issuing là async downstream sau `OrderPaid`; UI có thể suy ra trạng thái "đang phát hành vé" khi order đã `PAID` nhưng `/api/tickets` chưa có dữ liệu.

### Inventory reservation state

```mermaid
stateDiagram-v2
    [*] --> RESERVED
    RESERVED --> COMMITTED: OrderPaid consumed
    RESERVED --> RELEASED: OrderPaymentFailed or OrderExpired
    COMMITTED --> [*]
    RELEASED --> [*]
```

### Ticket issue state

```mermaid
stateDiagram-v2
    [*] --> EVENT_RECEIVED
    EVENT_RECEIVED --> DEDUP_CHECKED
    DEDUP_CHECKED --> ALREADY_PROCESSED: duplicate messageId or orderItemId
    DEDUP_CHECKED --> ISSUING: new order items
    ISSUING --> ISSUED: tickets inserted
    ISSUED --> EVENT_PUBLISHED: TicketsIssued published
    EVENT_PUBLISHED --> [*]
    ALREADY_PROCESSED --> [*]
```

## 7. Idempotency keys

| Layer | Key | Required behavior |
|---|---|---|
| Payment callback | gateway transaction id / payment intent id | Duplicate callback does not double mark order |
| Payment → Order | `messageId`, `paymentId`, `orderId` | Order transition to `PAID` exactly once |
| Order → Inventory | `messageId`, reservation/order state guard | Reservation commits once; duplicate `OrderPaid` is ACKed harmlessly |
| Order → Ticket | `(orderItemId, seat_sequence)` UNIQUE | Duplicate `OrderPaid` does not create duplicate tickets, including qty > 1 |
| TicketsIssued → Notification | `messageId`, `ticketId` | Duplicate notification suppressed or made harmless |

## 8. Error handling

| Failure | Handling | User-visible state |
|---|---|---|
| Invalid callback signature | Reject callback, no order update | Payment pending/failed depending gateway retry |
| Duplicate payment callback | Return success after replay check | No duplicate charge/ticket |
| Order already paid | Treat as idempotent success | Paid |
| RabbitMQ temporarily unavailable | Payment transaction committed; `PaymentSucceeded` remains in payment outbox and drainer retries | Payment accepted, order/ticket pending |
| Inventory commit fails after `OrderPaid` | Retry consumer; DLQ after max retries; do not roll back paid order automatically | Paid, ops alert |
| `ticket-service` fails after event delivery | Retry consumer; DLQ after max retries | Paid but ticket pending, ops alert |
| Duplicate `OrderPaid` event | ACK duplicate after dedup in Inventory/Ticket | No duplicate commit/ticket |
| `TicketsIssued` notification fails | Notification retry/DLQ | Ticket still issued |

## 9. Data consistency

- Payment result is transactional inside `payment-service`; order transition is asynchronous after `PaymentSucceeded`.
- Inventory commit is asynchronous after `OrderPaid`; `inventory-service` status guard prevents double commit.
- Ticket issue is asynchronous and eventually consistent after `OrderPaid`.
- UI should show paid/ticket pending state if tickets not issued yet.
- Reconciliation job can compare paid order items vs committed reservations vs issued tickets.

## 10. Observability

Required correlation/log fields:

- `requestId`
- `correlationId`
- `causationId`
- `messageId`
- `orderId`
- `paymentTransactionId`
- `ticketId`
- `concertId`
- `eventType`
- `durationMs`

Metrics:

- `payment_callback_total{result}`
- `order_paid_event_published_total`
- `ticket_issue_total{result}`
- `ticket_issue_duration_ms`
- `ticket_issue_duplicate_total`
- `ticket_issue_dlq_total`

## 11. Acceptance criteria

- [ ] Valid payment callback produces exactly one paid order.
- [ ] Valid `OrderPaid` commits inventory reservation exactly once.
- [ ] Valid paid order produces expected number of tickets.
- [ ] Duplicate payment callback does not duplicate tickets.
- [ ] Duplicate `OrderPaid` event does not duplicate inventory commit or tickets.
- [ ] `TicketsIssued` contains `ticketTypeName` and no raw `qrToken`.
- [ ] `notification-service` can consume `OrderPaid` and `TicketsIssued` without querying other service DB directly.
- [ ] If ticket issuing fails, event is retried or sent to DLQ with alert.

## 12. Open questions

- [x] `payment-service` publishes `PaymentSucceeded`; `order-service` consumes it asynchronously via `order.payment-succeeded`.
- [x] `OrderPaid` outbox is required and verified in `order-service`.
- [ ] Confirm whether `ticket-service` must implement outbox for `TicketsIssued` in MVP or accept at-least-once publish risk.
- [x] MVP order state does not require explicit `TICKET_PENDING` / `TICKET_ISSUED`; ticket issue is downstream async.
