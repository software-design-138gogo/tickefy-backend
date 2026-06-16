---
title: Auth Contract
status: DRAFT
version: 1.0
owner: Auth Service Owner
reviewers: []
lastUpdated: YYYY-MM-DD
---

# Auth Contract

## 1. Token type

- Algorithm:
- Access token TTL:
- Refresh token TTL:
- Public key distribution:

## 2. JWT claims

```json
{
  "sub": "user-uuid",
  "email": "user@example.com",
  "roles": ["AUDIENCE"],
  "jti": "token-uuid",
  "iat": 0,
  "exp": 0
}
```

## 3. Roles

| Role | Quyền |
|---|---|
| `AUDIENCE` | |
| `ORGANIZER` | |
| `CHECKIN_STAFF` | |
| `ADMIN` | |

## 4. Gateway propagation

```http
X-User-ID: <uuid>
X-User-Roles: AUDIENCE,ORGANIZER
X-Request-ID: <request-id>
```

## 5. Logout/revocation

- Blacklist strategy:
- TTL:
- Service nào kiểm tra blacklist:

## 6. Error codes

| Code | HTTP | Khi xảy ra |
|---|---:|---|
| `UNAUTHORIZED` | 401 | |
| `TOKEN_EXPIRED` | 401 | |
| `INVALID_TOKEN` | 401 | |
| `FORBIDDEN` | 403 | |
