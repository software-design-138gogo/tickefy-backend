# e-ticket-service — Real DB Tests (Testcontainers)

> Last updated: 2026-06-18  
> Command: `cd services/e-ticket-service && mvn -Preal-db-test verify`  
> DB: PostgreSQL `17-alpine` via Testcontainers

## Result

```text
Tests run: 12 (IT), Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Test Classes

### `TicketRepositoryIT` (6 tests)

| Test | Mục đích |
|---|---|
| `migrations_applyCleanly` | Flyway apply `eticket_service` schema không error |
| `save_thenFindByIdReturnsCorrect` | Insert ticket + lookup by id |
| `uniqueConstraint_orderItemIdSeatSequence` | `(orderItemId, seatSequence)` UNIQUE — duplicate throws exception |
| `atomicCheckin_whenIssued_thenUpdated` | Conditional update `status=ISSUED → SCANNED` |
| `atomicCheckin_whenAlreadyScanned_thenReturnsFalse` | Double check-in → update returns 0 rows |
| `findByOwner_doesNotExposeRawQrToken` | JPA query không select `qr_token` column trong public projection |

### `TicketApiIT` (6 tests)

| Test | Endpoint | Expected |
|---|---|---|
| `getTickets_returnsMasked` | `GET /api/tickets` | `qrTokenMasked` present, NO raw `qrToken` |
| `getTicket_returnsMasked` | `GET /api/tickets/{id}` | `ticketTypeName` field, NOT `ticketName` |
| `getQr_whenOwner_returnsRaw` | `GET /api/tickets/{id}/qr` | raw `qrToken` returned |
| `issue_whenValidPayload_thenIssued` | `POST /internal/tickets/issue` | 200, ticket created |
| `issue_whenReplay_thenIdempotent` | `POST /internal/tickets/issue` (same orderItemId) | 200, same result |
| `snapshot_doesNotLeakRaw` | `GET /internal/tickets/snapshot/{concertId}` | `qrTokenMasked` + `qrTokenHash`, NO raw `qrToken` |

## DB Schema Verified

```sql
-- tickets
CREATE TABLE eticket_service.tickets (
  id UUID PRIMARY KEY,
  order_item_id TEXT NOT NULL,
  seat_sequence INT NOT NULL,
  concert_id TEXT NOT NULL,
  ticket_type_id TEXT NOT NULL,
  ticket_type_name TEXT NOT NULL,
  holder_user_id TEXT NOT NULL,
  qr_token TEXT NOT NULL,         -- stored, KHÔNG exposed trong public DTO
  qr_token_masked TEXT NOT NULL,  -- exposed in public responses
  qr_token_hash TEXT NOT NULL,    -- for snapshot + checkin lookup
  status TEXT NOT NULL DEFAULT 'ISSUED',
  UNIQUE (order_item_id, seat_sequence)
);
```

## Chạy lại

```powershell
cd services/e-ticket-service
mvn -Preal-db-test verify

# Chỉ 1 IT class
mvn -Preal-db-test verify -Dit.test=TicketRepositoryIT
```
