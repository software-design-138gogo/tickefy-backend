# e-ticket-service — API Contract Tests (REST Assured)

> Last updated: 2026-06-18  
> Command: `cd services/e-ticket-service && mvn -Preal-db-test verify`  
> Class: `TicketApiIT`

## Result (2026-06-14)

```text
TicketApiIT: Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Contracts Covered

### `GET /api/tickets`

| # | Input | Expected |
|---|---|---|
| 1 | Valid JWT (owner) | 200, `data[].ticketTypeName` (NOT `ticketName`), `data[].qrTokenMasked`, NO raw `qrToken` |
| 2 | No JWT | 401 `UNAUTHORIZED` |

### `GET /api/tickets/{id}`

| # | Input | Expected |
|---|---|---|
| 1 | Valid JWT (owner) | 200, `data.ticketTypeName`, `data.qrTokenMasked` |
| 2 | Valid JWT (different user) | 403 `FORBIDDEN` |
| 3 | Non-existent id | 404 `TICKET_NOT_FOUND` |

### `GET /api/tickets/{id}/qr`

| # | Input | Expected |
|---|---|---|
| 1 | Valid JWT (owner) | 200, `data.qrToken` (raw — intentional, owner endpoint only) |
| 2 | Valid JWT (different user) | 403 `FORBIDDEN` |

### `POST /internal/tickets/issue`

| # | Input | Expected |
|---|---|---|
| 1 | Valid payload, role `ADMIN` | 200, tickets created |
| 2 | Duplicate `orderItemId`, same payload | 200, idempotent (same result returned) |
| 3 | Role `CHECKIN_STAFF` | 403 `FORBIDDEN` |
| 4 | Role `AUDIENCE` | 403 `FORBIDDEN` |

### `GET /internal/tickets/snapshot/{concertId}`

| # | Input | Expected |
|---|---|---|
| 1 | Valid concertId, role `CHECKIN_STAFF` | 200, `tickets[].qrTokenMasked`, `tickets[].qrTokenHash`, NO raw `qrToken` |

## QR Safety Assertion

```java
// Bất kỳ endpoint public nào KHÔNG được trả raw qrToken
assertThat(response.extract().body().asString())
    .doesNotContain("\"qrToken\":");

// Chỉ /qr endpoint mới có qrToken
given().header("Authorization", "Bearer " + ownerJwt)
    .get("/api/tickets/{id}/qr", ticketId)
    .then()
    .body("data.qrToken", notNullValue());  // ← đây là exception có chủ đích
```

## Chạy lại

```powershell
cd services/e-ticket-service
mvn -Preal-db-test verify -Dit.test=TicketApiIT
```
