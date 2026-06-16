---
title: Event Envelope
status: DRAFT
version: 1.0
owner: BE Lead
reviewers: []
lastUpdated: YYYY-MM-DD
---

# Event Envelope

## 1. Mục tiêu

Chuẩn hóa metadata chung của mọi message/event truyền qua RabbitMQ.

## 2. Envelope chuẩn

```json
{
  "messageId": "uuid-v4",
  "eventType": "OrderPaid",
  "eventVersion": "1.0",
  "source": "order-service",
  "occurredAt": "2026-06-16T10:00:00Z",
  "correlationId": "req-uuid",
  "causationId": "optional-parent-message-id",
  "payload": {}
}
```

## 3. Field definitions

| Field | Required | Mô tả |
|---|---|---|
| `messageId` | Yes | ID duy nhất cho một lần publish |
| `eventType` | Yes | Tên domain event |
| `eventVersion` | Yes | Phiên bản payload |
| `source` | Yes | Producer service |
| `occurredAt` | Yes | Thời điểm nghiệp vụ xảy ra |
| `correlationId` | Yes | Trace toàn flow |
| `causationId` | No | Message gây ra event hiện tại |
| `payload` | Yes | Dữ liệu nghiệp vụ |

## 4. Consumer rules

- Consumer idempotent theo `messageId`.
- Chỉ ACK sau khi transaction thành công.
- Retry có giới hạn.
- Quá retry chuyển DLQ.
- Không xử lý lại message đã hoàn thành.

## 5. Producer rules

- Event chỉ publish sau khi business transaction thành công.
- Nếu cần đảm bảo DB + event consistency, dùng Outbox Pattern.
- Không đưa file lớn vào payload; chỉ gửi object key/URL.
- Không đưa secret, JWT, full QR token vào event.

## 6. Compatibility

- Thêm optional field: non-breaking.
- Xóa/đổi tên field hoặc đổi type: breaking.
- Breaking change phải tăng `eventVersion`.
