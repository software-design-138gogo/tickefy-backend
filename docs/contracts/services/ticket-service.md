---
title: Service Specification - ticket-service
status: DRAFT
version: 1.0
owner: Hòa
reviewers: [BE Lead, Mobile, Notification]
lastUpdated: 2026-06-16
---

# Service Specification — `ticket-service`

> `ticket-service` là tên canonical trong contract. Implementation folder hiện tại trong backend là `services/e-ticket-service`. File này ưu tiên design đã chốt trong common contracts, không mô tả drift cũ là contract mới.

## 1. Identity

| Item | Value |
|---|---|
| Service name | `ticket-service` |
| Current implementation folder | `services/e-ticket-service` |
| Owner | Hòa |
| Repository | `tickefy-backend` |
| Internal port | 8083 (host) → 8080 (container) |
| Public base path | `/api/tickets` |
| Internal base path | `/internal/tickets` |
| Health check | `/actuator/health` |
| Swagger/OpenAPI | `/swagger-ui/index.html` when enabled |
| Database schema | `ticket_schema` target; verify implementation schema before freeze |

## 2. Responsibilities

### Service chịu trách nhiệm

- Là Source of Truth cho ticket record và ticket status.
- Issue ticket sau khi consume `OrderPaid`.
- Sinh và quản lý QR verification data cho ticket.
- Trả ticket display data cho audience.
- Verify ticket và thực hiện atomic check-in cho `checkin-service`.
- Cancel/refund/revoke ticket khi nhận event nghiệp vụ tương ứng.
- Publish `TicketsIssued` sau khi issue ticket thành công.
- Không expose raw `qrToken` qua public API/event/log.

### Service không chịu trách nhiệm

- Không reserve inventory.
- Không xử lý payment callback.
- Không sở hữu order state.
- Không gửi email/push trực tiếp nếu đã có `notification-service`.
- Không quyết định staff có được phân công gate/concert hay không nếu rule đó thuộc `checkin-service`.
- Không ghi check-in audit log chính; audit thuộc `checkin-service`.

## 3. Data ownership

### Tables owned

| Table | Purpose |
|---|---|
| `tickets` | Ticket issued cho từng order item / user / concert. Cột `seat_sequence` (migration V4) + composite UNIQUE `uq_tickets_order_item_seq (order_item_id, seat_sequence)` cho qty>1; entity `@Table(uniqueConstraints)` khớp constraint này |
| `ticket_qr_tokens` hoặc fields tương đương | QR token/hash/metadata phục vụ verify |
| `processed_messages` | Dedup RabbitMQ messages by `messageId` |
| `ticket_status_history` | Optional audit trạng thái ticket |

### Cross-service references

| Field | Source service | Validation strategy |
|---|---|---|
| `userId` | `auth-service` | Trust from `OrderPaid` payload after producer validation |
| `concertId` | `event-service` | Trust from `OrderPaid`; optional internal lookup for snapshot enrichment |
| `orderId` | `order-service` | Trust from `OrderPaid`; unique issue correlation |
| `orderItemId` | `order-service` | Part of idempotency key `(orderItemId, seat_sequence)` for ticket issue (qty>1 → N seats per item) |
| `ticketTypeId` | `inventory-service` | Trust from `OrderPaid`; no cross-schema FK |

### Invariants

- Không có cross-service foreign key.
- Service khác không query trực tiếp schema này.
- ✅ Idempotency key = **`(orderItemId, seat_sequence)` UNIQUE** (đã chốt + verified): order item quantity=N → N vé seq 1..N; redeliver `OrderPaid` không tạo vé trùng.
- Một ticket chỉ có một terminal state tại một thời điểm.
- Public response/event không chứa raw `qrToken`.

## 4. Dependencies

### Synchronous dependencies

| Service | Endpoint | Purpose | Timeout | Retry |
|---|---|---|---:|---|
| `event-service` | `GET /internal/concerts/{concertId}` | Optional enrich snapshot/concert validation | 2s | No retry in request path |
| `auth-service` | none in request path | JWT verified locally via public key | N/A | N/A |

### Infrastructure dependencies

| Dependency | Purpose |
|---|---|
| PostgreSQL | Store tickets, QR metadata, processed messages |
| Redis | Optional cache/idempotency accelerator; DB remains source of truth |
| RabbitMQ | Consume `OrderPaid`, `ConcertCancelled`; publish `TicketsIssued` |
| Object Storage | Optional QR/PDF assets if ticket PDF generation is owned here |

## 5. Public APIs

| Method | Path | Role | Description | Contract |
|---|---|---|---|---|
| GET | `/api/tickets` | `AUDIENCE` | List tickets of authenticated user | `ApiResponse<PagedResponse<TicketSummary>>` |
| GET | `/api/tickets/{ticketId}` | `AUDIENCE` | Get ticket detail if owned by authenticated user | `ApiResponse<TicketDetail>` |

### Public DTO notes

`TicketDetail` should include:

| Field | Required | Notes |
|---|---:|---|
| `ticketId` | Yes | UUID string |
| `orderId` | Yes | Cross-service reference |
| `concertId` | Yes | Must not be `eventId` |
| `ticketTypeId` | Yes | UUID string |
| `ticketTypeName` | Yes | Display name, not `ticketName` |
| `status` | Yes | `ISSUED`, `CHECKED_IN`, `CANCELLED`, `REFUNDED`, `REVOKED` |
| `issuedAt` | Yes | ISO-8601 UTC |
| `checkedInAt` | No | Null until checked in |
| `qrTokenMasked` | Conditional | Safe display/scan token representation; never raw token in logs |

## 6. Internal APIs

| Method | Path | Caller | Description | Contract |
|---|---|---|---|---|
| POST | `/internal/tickets/checkin` | `checkin-service` | Verify and atomically mark ticket checked-in | `ApiResponse<CheckinDecision>` |
| GET | `/internal/tickets/snapshot?concertId={concertId}` | `checkin-service` | Return valid tickets for offline snapshot | `ApiResponse<TicketSnapshotData>` |
| GET | `/internal/tickets/{ticketId}/status` | `checkin-service` optional | Read current ticket state | `ApiResponse<TicketStatusView>` |

### `POST /internal/tickets/checkin`

Request:

```json
{
  "concertId": "concert-uuid",
  "qrTokenMasked": "masked-or-derived-token",
  "staffId": "staff-uuid",
  "gate": "GATE_A",
  "scannedAt": "2026-06-16T10:00:00Z",
  "scanRequestId": "mobile-scan-id-or-null",
  "syncBatchId": null,
  "offlineScanId": null,
  "source": "ONLINE"
}
```

Rules:

- `source` is `ONLINE` or `OFFLINE`.
- Online requests require `scanRequestId`.
- Offline sync requests require `syncBatchId` and `offlineScanId`.
- `staffId` comes from the JWT verified by `checkin-service`; clients do not call this endpoint directly.
- Idempotency metadata is used for retry safety only; the guarded ticket state transition remains the correctness source.

Response uses check-in result semantics from `../common/checkin-result-catalog.md`.

## 7. Events published

*Lưu ý: Publish thông qua Outbox Pattern — ghi event vào bảng outbox cùng transaction với ticket state change, drainer publish sau.*

| Event | Routing key | When | Consumers (queue) | Contract |
|---|---|---|---|---|
| `TicketsIssued` | `tickets.issued` | Tickets created after `OrderPaid` | `notification-service` (`notification.tickets-issued`) | Payload: `{orderId, userId, concertId, tickets[{ticketId, orderItemId, ticketTypeId, ticketTypeName, status}], issuedAt}` — theo `../common/event-envelope.md` §14.3 |
| `TicketCheckedIn` | `ticket.checked-in` | Optional after successful check-in | Hiện chưa có consumer khai báo (analytics/optional trong tương lai) | Payload: `{ticketId, concertId, userId, staffId, gate, checkedInAt}` — theo `../common/event-envelope.md` §14.6 |

> ⚠️ **`TicketRevoked`:** State machine §9 có state `REVOKED` (admin revoke) nhưng hiện CHƯA có event `TicketRevoked` trong `event-envelope.md` và CHƯA có consumer nào khai báo. `REVOKED` hiện là state transition nội bộ. Nếu cần thông báo service khác (notification), phải bổ sung event contract vào `event-envelope.md` trước.
>
> ⚠️ **Notification queue naming:** `notification-service.md` §8 hiện khai báo queue name `notification-service.ticket-issued.queue` — sai convention (`event-envelope.md` §6.3 quy định `{consumer}.{event-name}` = `notification.tickets-issued`). **Cần align với nhóm notification-service (Dương).**

## 8. Events consumed

| Event | Producer | Queue | Behavior | Idempotency key |
|---|---|---|---|---|
| `OrderPaid` | `order-service` | `ticket.order-paid` | Issue tickets for order items (loop `quantity` → N vé/item) | **envelope** → đọc `payload.{orderId,userId,concertId,paidAt,items[{orderItemId,ticketTypeId,quantity,zoneId,ticketTypeName}]}`; idempotency `(orderItemId, seat_sequence)` |
| `ConcertCancelled` | `event-service` | `ticket.concert-cancelled` | Mark issued tickets for concert as `CANCELLED` | `messageId`, `concertId` |
| `OrderRefunded` | `order-service` | `ticket.order-refunded` | Mark tickets for refunded order as `REFUNDED` | `messageId`, `orderId` |

## 9. State machines

```mermaid
stateDiagram-v2
    [*] --> ISSUED
    ISSUED --> CHECKED_IN: atomic check-in accepted
    ISSUED --> CANCELLED: ConcertCancelled / order cancelled
    ISSUED --> REFUNDED: refund completed
    CANCELLED --> REFUNDED: refund completed
    ISSUED --> REVOKED: admin revoke
    CHECKED_IN --> [*]
    CANCELLED --> [*]
    REFUNDED --> [*]
    REVOKED --> [*]
```

### Transition table

| Current | Action/Event | Next | Side effects |
|---|---|---|---|
| none | `OrderPaid` consumed | `ISSUED` | Create ticket, publish `TicketsIssued` |
| `ISSUED` | Internal check-in accepted | `CHECKED_IN` | Return `ACCEPTED`, optional publish `TicketCheckedIn` |
| `CHECKED_IN` | Check-in replay/duplicate | `CHECKED_IN` | Return `DUPLICATE_REJECTED`, no state change |
| `ISSUED` | `ConcertCancelled` | `CANCELLED` | Ticket no longer valid |
| `ISSUED`/`CANCELLED` | `OrderRefunded` | `REFUNDED` | Ticket no longer valid; refund state wins over prior cancellation marker |
| `ISSUED` | Admin revoke | `REVOKED` | Ticket no longer valid. Hiện là state nội bộ; event `TicketRevoked` chưa được định nghĩa trong `event-envelope.md` |

## 10. Reliability

### Idempotency

- Consume `OrderPaid` idempotent by `(orderItemId, seat_sequence)` (composite UNIQUE; qty>1 = one ticket per seat).
- Duplicate `OrderPaid` must not create duplicate tickets.
- Internal check-in is atomic; concurrent scans of the same ticket produce exactly one `ACCEPTED`.
- Internal check-in dedup may use `(source, scanRequestId)` for `ONLINE` requests and `(syncBatchId, offlineScanId)` for `OFFLINE` requests when provided.
- Duplicate scan returns result code, not API error, if request is otherwise valid.

### Retry

- RabbitMQ consumers retry only transient failures.
- Duplicate messages are ACKed after idempotency check.
- Non-retryable contract errors go to DLQ with `messageId`, `eventType`, `correlationId`.

### Timeout

- Internal check-in transaction target timeout: ≤ 2s.
- Snapshot endpoint target timeout: ≤ 5s for typical concert size; large concerts should page/stream or precompute.

### Circuit breaker

- Not required for inbound event consumer.
- If ticket-service calls other services for enrichment, use short timeout and degrade without blocking check-in.

### Transaction boundaries

- Ticket issue and processed message insert should be in one DB transaction.
- Atomic check-in must update ticket status using a guarded update, e.g. only from `ISSUED`.
- No JVM-local lock such as `String.intern()` as production idempotency mechanism.

## 11. Cache

| Key pattern | Data | TTL | Invalidation |
|---|---|---:|---|
| `ticket:snapshot:{concertId}` | Optional precomputed snapshot | Short, e.g. 1-5 min | Ticket issued/cancelled/refunded/check-in |
| `ticket:status:{ticketId}` | Optional status cache | Short | Any ticket transition |

Cache is optional; PostgreSQL remains source of truth.

## 12. Security

- Authentication: public/internal protected endpoints use `Authorization: Bearer <access-token>`; Gateway verifies JWT at the edge when requests pass through Gateway; `ticket-service` still verifies RS256 locally. `checkin-service` forwards the original access token for internal calls made on behalf of a user. MVP has no separate service token/client-credentials flow.
- Authorization: audience can only access own tickets; `checkin-service` calls internal endpoints for staff operations.
- Sensitive data: raw `qrToken` must not leave trusted boundary unless explicitly required by implementation.
- Logging mask: log `qrTokenMasked`/prefix only; never log JWT, raw QR, secrets.

## 13. Environment variables

| Variable | Required | Example | Description |
|---|---|---|---|
| `SERVER_PORT` | Yes | `8083` | Service port |
| `DB_URL` / `DB_HOST` | Yes | `jdbc:postgresql://localhost:5432/tickefy` | PostgreSQL connection |
| `DB_SCHEMA` | Yes | `ticket_schema` | Owned schema |
| `JWT_PUBLIC_KEY_PATH` | Yes in prod | `/run/secrets/jwt-public.pem` | Verify bearer token |
| `RABBITMQ_HOST` | Yes | `localhost` | RabbitMQ host |
| `RABBITMQ_USERNAME` | Yes | `tickefy` | RabbitMQ user |
| `RABBITMQ_PASSWORD` | Yes | `change-me` | RabbitMQ password |

## 14. Observability

- Logs: `requestId`, `correlationId`, `messageId`, `eventType`, `ticketId`, `orderId`, `concertId`, `result`, `durationMs`.
- Metrics: tickets issued total, check-in decisions total by result, duplicate event total, DLQ total.
- Traces: propagate `correlationId` from events and HTTP request.
- Alerts: consumer DLQ > 0, ticket issue failure, check-in error rate, DB lock timeout.

## 15. Failure scenarios

| Scenario | Expected behavior | Error/event |
|---|---|---|
| Duplicate `OrderPaid` | ACK without duplicate tickets | metric `events_duplicate_total` |
| Ticket already checked in | Return business result | `DUPLICATE_REJECTED` |
| Ticket belongs to another concert | Return business result | `WRONG_EVENT` |
| Ticket cancelled/refunded | Return business result | `CANCELLED_REJECTED` / `REFUNDED_REJECTED` |
| QR malformed | API error | `INVALID_QR_TOKEN` |
| DB unavailable during event consume | Retry then DLQ | `SERVICE_UNAVAILABLE` internal/log |
| Unsupported event version | DLQ, alert owner | contract error log |

## 16. Integration acceptance criteria

- [ ] Health check pass.
- [ ] Swagger/OpenAPI available.
- [ ] API contract tests pass.
- [ ] Event contract tests pass for `OrderPaid`, `TicketsIssued`, `ConcertCancelled`.
- [ ] Duplicate `OrderPaid` does not duplicate tickets.
- [x] ✅ qty>1 verified (compose dev): order item quantity=3 → 3 vé `seat_sequence` 1/2/3; replay không nhân đôi.
- [ ] Concurrent check-in accepts exactly one request.
- [ ] Public ticket response does not expose raw `qrToken`.
- [ ] Docker image builds.
- [ ] `.env.example` complete.
- [ ] Gateway route configured.
- [ ] Queue/binding/DLQ configured.
- [ ] Integration test with dependencies passes.

## 17. Open questions

- [ ] Confirm final database schema name: `ticket_schema` vs existing implementation schema.
- [ ] Confirm whether `TicketCheckedIn` event is needed in MVP.
- [ ] Confirm whether ticket PDF/QR image generation belongs here or notification-service.
- [ ] Confirm raw QR token storage strategy: raw encrypted vs hash-only.
