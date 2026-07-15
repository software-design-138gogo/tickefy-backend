# e-ticket-service — Unit & Integration Tests

> Last updated: 2026-06-18  
> Command: `cd services/e-ticket-service && mvn test`

## Result

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Test Classes

### `TicketServiceTest` (10 tests)

| Test | Scenario | Expected |
|---|---|---|
| `issue_whenNewOrder_thenIssueNTickets` | Qty=2, fresh order | 2 tickets issued |
| `issue_whenDuplicateOrderPaid_thenIdempotent` | Replay cùng `orderItemId` | Không tạo thêm, không error |
| `issue_seatSequenceAssigned` | Qty=3 | seat_sequence 1, 2, 3 |
| `issue_uniqueConstraint_orderItemIdAndSeatSequence` | DB UNIQUE `(orderItemId, seatSequence)` | Exception nếu vi phạm |
| `getTicket_whenOwner_thenReturnsMasked` | Owner xem ticket | `qrTokenMasked`, NO raw `qrToken` |
| `getTicket_whenNotOwner_thenForbidden` | User khác xem ticket | 403 `FORBIDDEN` |
| `getQr_whenOwner_thenReturnsRawToken` | Owner call `/qr` endpoint | raw `qrToken` trả về |
| `getSnapshot_doesNotLeakRawQr` | Snapshot response | `qrTokenMasked` + `qrTokenHash`, NO raw `qrToken` |
| `cancelTicket_whenIssued_thenCancelled` | Cancel ticket còn ISSUED | status → `CANCELLED` |
| `cancelTicket_whenAlreadyScanned_thenRejected` | Cancel ticket đã SCANNED | error `TICKET_ALREADY_CHECKED_IN` |

### `OrderPaidConsumerTest` (8 tests)

| Test | Scenario | Expected |
|---|---|---|
| `consume_whenValidOrderPaid_thenIssueTickets` | Valid `order.paid` event | Tickets issued theo qty |
| `consume_whenQty2_thenTwoTickets` | qty=2 trong event | 2 tickets, seat_sequence 1+2 |
| `consume_whenDuplicateEvent_thenIdempotent` | Replay message | No duplicate, no error |
| `consume_whenInvalidEnvelope_thenDlq` | Malformed event | → DLQ `ticket-service.order-paid.queue.dlq` |
| `consume_publishesTicketsIssuedEvent` | After issue | `TicketsIssued` event published |
| `event_routingKey_isTicketsIssued` | Event payload | routing key = `tickets.issued` |
| `event_typeIs_TicketsIssued` | Event envelope | `type = TicketsIssued` |
| `event_doesNotContainRawQrToken` | Event payload | raw `qrToken` không có trong event |

### `TicketControllerSecurityTest` (10 tests)

| Test | Scenario | Expected |
|---|---|---|
| `list_whenNoToken_thenUnauthorized` | Không có JWT | 401 `UNAUTHORIZED` |
| `list_whenWrongIssuer_thenInvalidToken` | iss sai | 401 `INVALID_TOKEN` |
| `list_whenWrongAudience_thenInvalidToken` | aud sai | 401 `INVALID_TOKEN` |
| `list_whenExpired_thenTokenExpired` | Hết hạn | 401 `TOKEN_EXPIRED` |
| `list_whenTamperedSignature_thenInvalidToken` | Chữ ký sai | 401 `INVALID_TOKEN` |
| `internalIssue_whenCheckinStaff_thenForbidden` | CHECKIN_STAFF → POST /internal/tickets/issue | 403 |
| `internalIssue_whenAdmin_thenAllowed` | ADMIN → POST /internal/tickets/issue | 200 |
| `internalByToken_whenCheckinStaff_thenAllowed` | CHECKIN_STAFF → GET /internal/tickets/by-token | 200 |
| `logMasking_byTokenPath_doesNotLogRawToken` | Path log | `{qrTokenMasked}` in log, raw token absent |
| `spoofedXUserHeader_thenIgnored` | Header `X-User-Id` spoof | Identity từ JWT |

## Chạy lại

```powershell
cd services/e-ticket-service
mvn test

# Chỉ 1 class
mvn test -Dtest=TicketServiceTest
mvn test -Dtest=OrderPaidConsumerTest
```
