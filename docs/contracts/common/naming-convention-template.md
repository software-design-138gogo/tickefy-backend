---
title: Naming Convention
status: DRAFT
version: 1.0
owner: BE Lead
reviewers: []
lastUpdated: YYYY-MM-DD
---

# Naming Convention

## 1. ID

| Đối tượng | Quy ước |
|---|---|
| Concert | `concertId` |
| Order | `orderId` |
| Payment | `paymentId` |
| Reservation | `reservationId` |
| Ticket | `ticketId` |
| Message/event occurrence | `messageId` |
| Correlation | `correlationId` |

## 2. Time

- Dùng UTC ISO-8601.
- Tên field kết thúc bằng `At`.
- Ví dụ: `createdAt`, `paidAt`, `occurredAt`.

## 3. Money

- Dùng integer VND.
- Field: `amount`, `unitPrice`, `totalAmount`.
- Không dùng float/double.

## 4. Enum

- Dùng `UPPER_SNAKE_CASE`.
- Không đổi tên enum đã freeze nếu chưa version contract.

## 5. REST resource

```text
/api/concerts
/api/orders
/api/payments
/api/tickets
/api/checkins
```

## 6. RabbitMQ

| Thành phần | Quy ước |
|---|---|
| Exchange | `tickefy.events` |
| Routing key | lower-case dot notation, ví dụ `order.paid` |
| Queue | `{service}.{event}`, ví dụ `ticket.order-paid` |
| DLQ | `{queue}.dlq` |

## 7. Database

- Table/column: `snake_case`.
- Java/TypeScript field: `camelCase`.
- Không tạo cross-service foreign key.
