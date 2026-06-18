# checkin-service — Security Tests

> Last updated: 2026-06-18  
> Command: `cd services/checkin-service && mvn test -Dtest=CheckinControllerSecurityTest`

## Result

```text
CheckinControllerSecurityTest: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

## JWT Contract Tests

| Test | Token | Expected |
|---|---|---|
| Valid JWT, role `CHECKIN_STAFF` | iss=`tickefy-auth-service`, aud=`tickefy-api` | 200 |
| Wrong issuer | iss=`evil-service` | 401 `INVALID_TOKEN` |
| Wrong audience | aud=`wrong-api` | 401 `INVALID_TOKEN` |
| Expired token | `exp` < now | 401 `TOKEN_EXPIRED` |
| Tampered signature | Valid payload, sai chữ ký | 401 `INVALID_TOKEN` |
| Garbage token | `"notavalidjwt"` | 401 `INVALID_TOKEN` |
| No token | Không có `Authorization` header | 401 `UNAUTHORIZED` |

## Role Access Matrix

| Endpoint | `CHECKIN_STAFF` | `ADMIN` | `ORGANIZER` | `AUDIENCE` |
|---|:---:|:---:|:---:|:---:|
| `POST /api/checkin/scan` | ✅ 200 | ✅ 200 | ❌ 403 | ❌ 403 |
| `GET /api/checkin/snapshot/{id}` | ✅ 200 | ✅ 200 | ❌ 403 | ❌ 403 |
| `POST /api/checkin/sync` | ✅ 200 | ✅ 200 | ❌ 403 | ❌ 403 |
| `GET /api/checkin/events/{id}` | ✅ 200 | ✅ 200 | ❌ 403 | ❌ 403 |

## Spoofed Header Test

Verify `X-User-Id` / `X-User-Role` header bị spoof không ảnh hưởng authorization:

```java
// Test: gửi X-User-Id của admin nhưng JWT role = AUDIENCE
given()
    .header("Authorization", "Bearer " + audienceJwt)
    .header("X-User-Id", adminUserId)   // spoof
    .header("X-User-Role", "ADMIN")     // spoof
.when()
    .post("/api/checkin/scan")
.then()
    .statusCode(403);  // vẫn bị từ chối theo JWT role
```

**Kết quả:** ✅ Service dùng `SecurityContext` từ JWT, bỏ qua `X-User-*` headers.

## Chạy lại

```powershell
cd services/checkin-service
mvn test -Dtest=CheckinControllerSecurityTest
```
