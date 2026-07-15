---
title: Auth Contract
status: ACCEPTED
version: 1.0
owner: Hiệp (auth-service)
reviewers: [Dương, Hòa, Hoàng]
lastUpdated: 2026-06-17
---

# Auth Contract

> Contract xác thực/uỷ quyền cho mọi service + client. Endpoint + implementation note ở `../services/auth-service.md`. Format response chung ở `./api-standard.md`.

## 1. Token type
- **Algorithm:** RS256 (bất đối xứng). auth-service giữ **private key** (ký); API Gateway và service khác **verify-only** bằng **public key**.
- **Access token TTL:** 15 phút (`PT15M`).
- **Refresh token TTL:** 7 ngày (`P7D`). Refresh = **opaque random** (KHÔNG phải JWT), lưu **SHA-256 hash** trong DB (không plaintext).
- **Public key distribution:** API Gateway và service verify cần public key. Dev: keypair throwaway commit (classpath). Prod: nạp qua `JWT_PUBLIC_KEY_PATH`; **private key KHÔNG commit** (`.gitignore *-prod*.pem`).

## 1b. Token transport (body + cookie)
- **access token:** trả trong **body** login → client lưu **memory** → gắn `Authorization: Bearer` trên **mọi call**. Đây là đường auth **cross-service THẬT**: Gateway verify ở biên hệ thống nếu request đi qua Gateway, và service nhận request vẫn verify lại header này.
- **refresh token:** **HttpOnly cookie** (auth-service Set-Cookie; refresh endpoint đọc cookie) + body (tương thích ngược). Client web **KHÔNG lưu refresh từ body**.
- **access-cookie:** auth-service có set (HttpOnly, Path `/`) nhưng **forward-looking** (prod-qua-gateway) — cross-service KHÔNG dùng cookie.
- **CORS:** `allowCredentials=true` + origins từ env (KHÔNG `*`); rỗng = off (gateway sở hữu CORS prod). Chi tiết: `../services/auth-service.md`.

## 2. JWT claims
```json
{
  "iss": "tickefy-auth-service",
  "aud": "tickefy-api",
  "sub": "user-uuid",      // userId
  "email": "user@example.com",
  "roles": ["AUDIENCE"],   // nhúng lúc login — xem cảnh báo §3
  "jti": "token-uuid",     // dùng cho blacklist
  "iat": 0,                // issued-at (epoch giây)
  "exp": 0                 // expiry (epoch giây)
}
```
Gateway và service phải verify chữ ký, `exp`, `iss` và `aud` TRƯỚC khi tin claims; sai → 401 `INVALID_TOKEN`.

## 3. Roles
| Role | Quyền | Client chính |
|---|---|---|
| `AUDIENCE` (mặc định khi register) | Duyệt/mua vé, xem vé của mình | tickefy-web (customer) |
| `ORGANIZER` | Tạo/sửa/quản lý concert + ticket type **của mình** | apps/admin |
| `CHECKIN_STAFF` | Soát/check-in vé tại cổng | tickefy-mobile |
| `ADMIN` | Toàn quyền — quản lý user/role, thấy tất cả concert | apps/admin |

- Authority Spring = `ROLE_{code}`.
- ⚠️ **Role thay đổi chỉ hiệu lực AUTHORITY ở token LẦN SAU** (roles nhúng vào access token lúc login). `GET /auth/me` đọc role từ **DB tươi** (hiện role mới ngay), nhưng `@PreAuthorize`/authority các endpoint khác vẫn theo token hiện tại. Không revoke refresh khi đổi role (🔭 PLANNED — parked).

### 3.1. Ticket / check-in role mapping

| Operation | Required role | Identity source |
|---|---|---|
| User xem vé của mình | `AUDIENCE` | `userId` từ JWT `sub` |
| Staff scan online | `CHECKIN_STAFF` | `staffId` từ JWT `sub` |
| Staff tải offline snapshot | `CHECKIN_STAFF` | `staffId` từ JWT `sub` |
| Staff sync offline scans | `CHECKIN_STAFF` | `staffId` từ JWT `sub` |
| Organizer xem thống kê check-in concert của mình | `ORGANIZER` | `organizerId` từ JWT `sub`, ownership validate qua event-service/checkin-service contract |
| Admin quản trị ticket/check-in nếu có | `ADMIN` | `userId` từ JWT `sub` |

Quy tắc bắt buộc:

* Không nhận `userId`, `staffId`, `organizerId` từ request body/query để quyết định quyền.
* `checkin-service` phải lấy `staffId` từ SecurityContext/JWT và ghi audit log.
* Service-to-service call đại diện user vẫn forward `Authorization: Bearer`; downstream tự verify token theo RS256.
* Nếu cần permission chi tiết theo concert/gate, checkin-service validate bằng assignment/permission data của nó hoặc gọi service sở hữu dữ liệu — không tin client tự khai.

## 4. Gateway and service verification

Quy tắc chốt cho protected endpoint:

1. Auth Service ký access token bằng RS256 private key.
2. API Gateway verify token trước khi route nếu request đi qua Gateway.
3. Gateway reject sớm request thiếu token, sai chữ ký, hết hạn hoặc sai `issuer`/`audience`.
4. Gateway giữ nguyên `Authorization: Bearer <access-token>` khi forward.
5. Mỗi protected service tự verify lại JWT bằng public key trước khi xử lý.
6. Service tự kiểm tra role, ownership và business authorization trên resource.
7. Không tạo thêm service token/client-credentials riêng cho MVP.
8. Internal synchronous call đại diện user forward access token gốc.

Gateway có thể forward identity headers để logging/debug:
```http
X-User-ID: <uuid>
X-User-Roles: AUDIENCE,ORGANIZER
X-Request-ID: <request-id>
```

Nhưng service **không được dùng `X-User-*` làm nguồn xác thực hoặc phân quyền duy nhất**. Identity tin cậy của service phải lấy từ JWT đã verify (`sub`, `roles`, `exp`, và các claim cấu hình khác). `X-Request-ID` vẫn là tracing header, không phải auth header.

## 5. Logout / revocation
- **Blacklist strategy:** logout → blacklist `jti` của access token trong Redis: `SET tickefy:auth:token:blacklist:{jti} "1" EX <ttl còn lại>` + revoke refresh token (`revokeAllForUser`).
- **TTL:** = thời gian còn lại của access token (key tự hết khi token hết hạn).
- **Service nào kiểm tra blacklist:** ⚠️ **HIỆN TẠI chỉ auth-service** (`JwtAuthenticationFilter`) check blacklist trên endpoint của nó. Gateway và service khác verify chữ ký + `exp` bằng public key nhưng **KHÔNG check blacklist** nếu chưa tích hợp Redis blacklist → một access token đã logout vẫn có thể được chấp nhận **tới khi hết hạn (≤15')**. Gateway-side blacklist check = 🔭 PLANNED (ADR-AUTH-003, parked).
- **Redis down fail-safe:** `isBlacklisted` lỗi → trả `false` (cho qua nếu chữ ký + `exp` hợp lệ) + log WARN; không chặn toàn hệ thống vì Redis chết.

## 6. Error codes
| Code | HTTP | Khi xảy ra |
|---|---:|---|
| `UNAUTHORIZED` | 401 | Thiếu/sai `Authorization` (chưa xác thực) |
| `INVALID_TOKEN` | 401 | Chữ ký/`exp` sai hoặc token hỏng — **gồm token HẾT HẠN** |
| `TOKEN_REVOKED` | 401 | Token bị blacklist (đã logout) / refresh bị revoke |
| `INVALID_CREDENTIALS` | 401 | Login sai email/mật khẩu (constant-time, chống user-enumeration) |
| `FORBIDDEN` | 403 | Đã xác thực nhưng không đủ quyền (sai role) |
| `EMAIL_ALREADY_EXISTS` | 409 | Register email trùng |
| `LAST_ADMIN` | 409 | Gỡ role ADMIN của admin enabled **cuối cùng** (chống khoá hệ thống) |
| `INVALID_ROLE` | 400 | Role không thuộc enum 4 role |
| `USER_NOT_FOUND` | 404 | User không tồn tại (role management) |

> ⚠️ **KHÔNG có `TOKEN_EXPIRED`.** Token hết hạn trả `INVALID_TOKEN`. Client refresh trên **BẤT KỲ 401** (thử 1 lần, loại trừ endpoint refresh) — không key vào một mã cụ thể.

## 7. Tài liệu liên quan
- `../services/auth-service.md` — contract endpoint auth (login/refresh/logout, cookie/CORS, user/role mgmt) — SSOT per-service, đang điền.
- `./api-standard.md` — format envelope/error chung.
- `./error-catalog.md` §3 (auth) — danh mục mã lỗi auth đầy đủ.
