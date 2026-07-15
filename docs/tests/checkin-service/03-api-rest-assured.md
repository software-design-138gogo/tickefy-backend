# checkin-service — API Contract Tests (REST Assured)

> Last updated: 2026-06-18  
> Command: `cd services/checkin-service && mvn -Preal-db-test verify`  
> Class: `CheckinApiIT` — full HTTP stack với real Postgres (Testcontainers) + Spring Boot test server

## Result (2026-06-14)

```text
CheckinApiIT: Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Contracts Covered

### `POST /api/checkin/scan`

| # | Input | Expected HTTP | Expected `data.result` |
|---|---|---|---|
| 1 | Valid QR + đúng concertId + role `CHECKIN_STAFF` | 200 | `ACCEPTED` |
| 2 | Same QR scan lần 2 | 200 | `DUPLICATE_REJECTED` |
| 3 | QR thuộc concertId khác | 200 | `WRONG_EVENT` |
| 4 | Ticket status `CANCELLED` | 200 | `CANCELLED_REJECTED` |
| 5 | Ticket status `REFUNDED` | 200 | `REFUNDED_REJECTED` |
| 6 | Không có JWT | 401 | — (`error.code = UNAUTHORIZED`) |

> **Rule:** Scan rejection (DUPLICATE, WRONG_EVENT, CANCELLED, REFUNDED) → `success=true`, `data.result=<code>`.  
> Auth/system error → `success=false`, `error.code=<code>`.

### `GET /api/checkin/snapshot/{concertId}`

| # | Input | Expected |
|---|---|---|
| 1 | Valid concertId + role `CHECKIN_STAFF` | 200, `data.concertId` correct, `tickets[].qrTokenMasked` present, NO raw `qrToken` |
| 2 | ConcertId không tồn tại | 404 `SNAPSHOT_NOT_FOUND` |

### `POST /api/checkin/sync`

| # | Input | Expected |
|---|---|---|
| 1 | Valid offline batch | 200, `data.accepted` populated, `data.rejected[].qrTokenMasked` present, NO raw `qrToken` in response |

## QR Token Safety Assertion

Mỗi response đều được assert:

```java
// Không có raw qrToken trong sync response
assertThat(response.extract().body().asString())
    .doesNotContain("\"qrToken\":");

// Có qrTokenMasked
assertThat(response.jsonPath().getString("data.accepted[0].qrTokenMasked"))
    .isNotNull();
```

## Chạy lại

```powershell
cd services/checkin-service
mvn -Preal-db-test verify -Dit.test=CheckinApiIT
```
