# 2026-06-17 e-ticket-service + checkin-service stabilization evidence

Status: PASS
Date: 2026-06-17

Scope: `auth-service`, `e-ticket-service`, `checkin-service`, with `inventory-service` and `order-service` used only to drive a real paid-order flow.

This evidence intentionally does not record raw QR token values. Assertions checked raw QR only in memory when required for the owner QR endpoint and scan flow.

## Commands run

Unit/service tests from repo `tickefy-backend`:

```powershell
cd services/auth-service
.\mvnw.cmd test

cd services/e-ticket-service
.\mvnw.cmd test

cd services/checkin-service
.\mvnw.cmd test
```

Results:

| Service | Result |
|---|---|
| `auth-service` | PASS, `82` tests, `0` failures, `0` errors |
| `e-ticket-service` | PASS, `28` tests, `0` failures, `0` errors |
| `checkin-service` | PASS, `19` tests, `0` failures, `0` errors |

Local integration stack from repo `tickefy-infrastructure`, folder `local`:

```powershell
docker compose --env-file .env.example -f docker-compose.dev.yml up -d --build
```

Notes:

- `local/scripts/up.sh dev` was not used in this run because local shell execution hit CRLF in the script.
- `.env.example` was used because the local `.env` was stale and did not include every service variable needed for this pass.
- RabbitMQ definitions were imported in the running `tickefy-rabbitmq` container before the smoke flow.
- The compose file used for this pass is `local/docker-compose.dev.yml`; `docker-compose.image.yml` was not used because this pass validates local source changes.

## JWT contract checks

Verified by tests and smoke setup:

- New auth tokens include `iss=tickefy-auth-service` and `aud=tickefy-api`.
- `auth-service` JWT tests cover wrong issuer, wrong audience, expired token, tampered token, and garbage token paths returning `INVALID_TOKEN`.
- `e-ticket-service` and `checkin-service` validators are configured to verify JWT signature, expiration, issuer, and audience.
- `e-ticket-service` and `checkin-service` controller security tests cover wrong issuer and wrong audience returning `INVALID_TOKEN`.
- Protected smoke routes used real `Authorization: Bearer <access-token>` tokens.
- `X-User-*` headers are not trusted for identity or authorization.
- `order-service` and `inventory-service` local compose env were aligned to `tickefy-auth-service` issuer for this smoke pass; those services do not yet validate audience in source.
- Gateway source was not present under `tickefy-backend/services` in this workspace.

## E-ticket smoke scenarios

Real API flow:

- Created/logged in customer through `auth-service`.
- Used dev inventory seed concert and ticket type to create an order.
- Simulated paid order through `order-service` dev endpoint.
- `e-ticket-service` consumed `OrderPaid` and issued `2` tickets for quantity `2`.

Verified:

- Ticket list/detail returned `ticketTypeName` and `qrTokenMasked`.
- Ticket list/detail did not return `ticketName` or raw `qrToken`.
- Owner QR endpoint returned raw `qrToken` plus `ticketId` and `qrTokenMasked`.
- Snapshot returned `qrTokenMasked` and `qrTokenHash`, not raw `qrToken`.
- `TicketsIssued` unit coverage verifies envelope event type `TicketsIssued`, routing key `tickets.issued`, source `ticket-service`, version `1.0`, batch payload, stable child `messageId`, and no QR fields.
- Replayed/duplicate issue path remains idempotent in tests.
- Request log masking converts `/internal/tickets/by-token/{token}` to `/internal/tickets/by-token/{qrTokenMasked}`.

## Checkin smoke scenarios

Real API flow:

- Created staff user and assigned `CHECKIN_STAFF`.
- Called checkin snapshot endpoint through `checkin-service`.
- Scanned ticket tokens returned by the owner QR endpoint.
- Ran sync flow using mobile-style raw QR input.

Verified:

- Snapshot chain returned `qrTokenMasked` and `qrTokenHash`, not raw `qrToken`.
- Online scan result sequence was `WRONG_EVENT -> ACCEPTED -> DUPLICATE_REJECTED`.
- Sync response kept the current array shape and returned `SYNC_ACCEPTED`.
- Sync response returned masked QR value, not raw `qrToken`.
- Checkin logs used `qrMasked=...` and did not include raw QR token values.

## Docker cleanup

Removed only old Tickefy containers/images used by this test pass:

- stopped containers: `tickefy-rabbitmq`, `tickefy-postgres`, `tickefy-redis`
- old images: `tickefy/checkin-service:evidence`, `tickefy/e-ticket-service:evidence`, `tickefy-checkin-service:latest`, `tickefy-e-ticket-service:latest`

Unrelated local images/containers such as `9router` and `dsvtn-*` were left untouched.
