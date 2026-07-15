# e-ticket-service — Security Tests

> Last updated: 2026-06-18  
> Command: `cd services/e-ticket-service && mvn test -Dtest=TicketControllerSecurityTest`

## Result

```text
TicketControllerSecurityTest: Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

## JWT Contract Tests

| Test | Token | Expected |
|---|---|---|
| Valid JWT, role `AUDIENCE` | iss=`tickefy-auth-service`, aud=`tickefy-api` | 200 (own tickets) |
| Wrong issuer | iss=`evil-service` | 401 `INVALID_TOKEN` |
| Wrong audience | aud=`wrong-api` | 401 `INVALID_TOKEN` |
| Expired token | `exp` < now | 401 `TOKEN_EXPIRED` |
| Tampered signature | Valid payload, sai chữ ký | 401 `INVALID_TOKEN` |
| Garbage token | `"notavalidjwt"` | 401 `INVALID_TOKEN` |
| No token | Không có `Authorization` header | 401 `UNAUTHORIZED` |

## Role Access Matrix

| Endpoint | `AUDIENCE` | `CHECKIN_STAFF` | `ORGANIZER` | `ADMIN` |
|---|:---:|:---:|:---:|:---:|
| `GET /api/tickets` | ✅ (own) | ✅ | ✅ | ✅ |
| `GET /api/tickets/{id}` | ✅ (own) | ✅ | ✅ | ✅ |
| `GET /api/tickets/{id}/qr` | ✅ (own) | ❌ 403 | ❌ 403 | ✅ |
| `POST /internal/tickets/issue` | ❌ 403 | ❌ 403 | ✅ | ✅ |
| `GET /internal/tickets/by-token/**` | ❌ 403 | ✅ | ❌ 403 | ✅ |
| `GET /internal/tickets/snapshot/**` | ❌ 403 | ✅ | ❌ 403 | ✅ |

## Spoofed Header Test

```java
// Test: JWT role = AUDIENCE, spoof X-User-Role = ADMIN
given()
    .header("Authorization", "Bearer " + audienceJwt)
    .header("X-User-Role", "ADMIN")     // spoof
.when()
    .post("/internal/tickets/issue")
.then()
    .statusCode(403);  // Role lấy từ JWT, không từ header
```

## Log Masking Test

```java
// Request path /internal/tickets/by-token/{rawToken} phải được log thành {qrTokenMasked}
// Verify bằng custom log appender hoặc RequestLoggingFilter test
assertThat(capturedLogEntry)
    .contains("qrTokenMasked")
    .doesNotContain(rawQrToken);
```

## Chạy lại

```powershell
cd services/e-ticket-service
mvn test -Dtest=TicketControllerSecurityTest
```
