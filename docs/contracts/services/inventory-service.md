---
title: Service Specification - inventory-service
status: DRAFT
version: 1.0
owner: Hiệp
reviewers: [Dương, Hòa, Hoàng]
lastUpdated: 2026-06-16
---

# Service Specification — `inventory-service`

> Nhãn: ✅ khớp implement (session) · 🔭 PLANNED/Pass 2 (chưa code, cố ý) · ⚠️ VERIFY (tái dựng — Claude Code đối chiếu repo).
> ⚠️ Trạng thái build (báo cáo integration v2): **LIVE (P1)** = ticket-type CRUD + availability + **reserve** (Lua atomic). **CHƯA code (Pass 2, 🔭)** = consume OrderPaid/OrderPaymentFailed/OrderExpired (commit/release qua event) + TTL release worker + consume Concert*.

## 1. Identity
| Item | Value |
|---|---|
| Service name | inventory-service |
| Owner | Hiệp |
| Repository | tickefy-backend → `services/inventory-service` ✅ |
| Internal port | 8083 (host) → 8080 (container) |
| Public base path | `/api/inventory/**` · ⚠️ ticket-type ở `/events/{concertId}/ticket-types/**` (caveat: nằm dưới `/events/` — trùng namespace Event; đã biết) |
| Health check | `/actuator/health` ✅ + `/health` |
| Swagger/OpenAPI | springdoc `/swagger-ui.html` ✅ (dep trong pom) |
| Database name / schema | DB `tickefy_inventory` · schema `inventory_service` (`${DB_SCHEMA}`) ✅ |

## 2. Responsibilities
### Chịu trách nhiệm
- Quản lý ticket type (giá, total, sale window, per-user limit). **Per-user limit thuộc Inventory** (quyết định đã khoá — KHÔNG phải Order).
- Theo dõi available / reserved / sold.
- **Reserve** vé atomic (Redis+Lua: check stock + check per-user limit + trừ cả hai trong 1 thao tác) — chống over-sell + lách limit dưới tải cao.
- **Commit** (thanh toán xong) / **Release** (timeout/fail) reservation.
- Đọc số vé còn (availability) từ Redis.
- Ngừng bán khẩn cấp khi `ConcertCancelled` (🔭 chờ Event).

### KHÔNG chịu trách nhiệm
- Concert metadata + seat-map (Event/Dương — chỉ tham chiếu `concertId`).
- Payment, order lifecycle.
- **Điều phối refund/hủy order** khi ConcertCancelled — **Order điều phối** (cách B); Inventory chỉ ngừng bán + release theo lệnh Order.
- **Publish event** — Inventory KHÔNG publish (phản hồi HTTP đồng bộ cho Order).

## 3. Data ownership
### Tables owned ✅ (`V2__inventory_schema.sql`)
| Table | Purpose |
|---|---|
| `ticket_types` | name (SVIP/VIP/CAT1/CAT2/GA), price, total_quantity, per_user_limit, sale_start_at, sale_end_at, concert_id |
| `ticket_type_inventory` | available / reserved / sold counts |
| `ticket_reservations` | status (RESERVED/COMMITTED/RELEASED), qty, user_id, expires_at |

### Cross-service references
| Field | Source service | Validation strategy |
|---|---|---|
| `concertId` | Event (Dương) | UUID v4, validate qua Event API (concert tồn tại + PUBLISHED) — ⚠️ Event CHƯA build → hiện validation skip/bỏ qua (🔭 bật khi Event có) |
| `ticketTypeId` | Event (= `concert_zones.id`) | UUID v4 từ Event; Inventory lấy làm PK, KHÔNG tự sinh (chốt với Dương). Dev seed tự đặt id cố định khi chưa có Event |
| `userId` | auth | Từ JWT claim, không FK |

### Invariants
- Không cross-service FK. `available` không bao giờ < 0 (Lua atomic). Per-user limit không bị vượt dù song song. **RESERVED + COMMITTED đều tính là "đã sở hữu"** cho per-user check.

## 4. Dependencies
### Synchronous dependencies
| Service | Endpoint | Purpose | Timeout | Retry |
|---|---|---|---:|---|
| Event (Dương) | GET concert | Validate concertId PUBLISHED khi tạo ticket type | ⚠️ | 🔭 Event chưa build → chưa gọi thật |

### Infrastructure dependencies
| Dependency | Purpose |
|---|---|
| PostgreSQL | **Source of truth** (ticket_types, inventory counts, reservations) |
| Redis | Cổng atomic Lua (stock + per-user quota) + cache availability |
| RabbitMQ | 🔭 Consume OrderPaid/OrderPaymentFailed/OrderExpired/Concert* — **Pass 2, chưa wire** |
| Object Storage | (none) |

## 5. Public APIs
| Method | Path | Role | Description | Contract |
|---|---|---|---|---|
| POST | `/events/{concertId}/ticket-types` | ORGANIZER/ADMIN | Tạo ticket type + khởi tạo inventory + seed Redis | inventory.md ✅ |
| GET | `/events/{concertId}/ticket-types` | public/auth | Danh sách ticket type | ✅ (FE 2.19 dùng) |
| GET | `/events/{concertId}/ticket-types/{ttId}/availability` | public | Số vé còn (đọc Redis) | ✅ |

## 6. Internal APIs (gọi từ Order, service-to-service Bearer)
| Method | Path | Caller | Description | Contract |
|---|---|---|---|---|
| POST | `/inventory/reservations` | Order | Reserve atomic (Lua) → `{reservationId,qty,expiresAt}` | ✅ **LIVE** (saga reserve sync) |
| GET | `/inventory/users/{userId}/purchase-limits` | Order/Admin | Quota còn lại | ✅ (`PurchaseLimitController.java:17,26`) |

> **Commit / Release: KHÔNG có HTTP endpoint** (cách B — event-only). Inventory commit (RESERVED→COMMITTED) / release qua **consume event** `OrderPaid`/`OrderPaymentFailed`/`OrderExpired` (Pass 2, xem §8), KHÔNG expose `/commit`/`/release` HTTP.

## 7. Events published
| Event | Routing key | When | Consumers | Contract |
|---|---|---|---|---|
| (none) | — | — | — | Inventory KHÔNG publish — phản hồi HTTP đồng bộ ✅ |

## 8. Events consumed — 🔭 CHƯA WIRE (Pass 2). Đây là kênh DUY NHẤT để commit/release (KHÔNG HTTP — §6)
| Event | Producer | Queue | Behavior | Idempotency key |
|---|---|---|---|---|
| `OrderPaid` | order | `inventory-service.order-paid.queue` | RESERVED→COMMITTED, sold+=qty, reserved-=qty | reservation/order_item — 🔭 Pass 2 |
| `OrderPaymentFailed` | order | `inventory-service.order-payment-failed.queue` | Release reservation | 🔭 Pass 2 |
| `OrderExpired` | order | `inventory-service.order-expired.queue` | Release reservation | 🔭 Pass 2 |
| `ConcertPublished` | event (Dương) | `inventory-service.concert-published.queue` | Chuẩn bị counter | 🔭 chờ Event |
| `ConcertCancelled` | event (Dương) | `inventory-service.concert-cancelled.queue` | **Ngừng bán khẩn cấp** (khóa tạo reservation mới); release đơn cũ do Order điều phối | idempotent theo concertId — 🔭 chờ Event |
> ⚠️ Khi wire Pass 2: **thêm DLQ + `setDefaultRequeueRejected(false)`** (tránh poison message — bài học từ e-ticket Hòa). Routing key/queue theo api-contracts §5.

## 9. State machines — reservation
```mermaid
stateDiagram-v2
    [*] --> RESERVED: reserve (Lua atomic OK)
    RESERVED --> COMMITTED: OrderPaid (commit)
    RESERVED --> RELEASED: TTL hết hạn (15') / OrderPaymentFailed / OrderExpired
    COMMITTED --> [*]
    RELEASED --> [*]
```
| Current | Action/Event | Next | Side effects |
|---|---|---|---|
| (none) | reserve OK | RESERVED | Redis: DECRBY stock, INCRBY user quota; PG: insert reservation, reserved_count+=qty, expires_at=now+15' |
| RESERVED | OrderPaid | COMMITTED | sold_count+=qty, reserved_count-=qty (idempotent nếu đã COMMITTED) — 🔭 Pass 2 |
| RESERVED | TTL / payment-failed / expired | RELEASED | Redis: INCRBY stock, DECRBY quota; PG: reserved_count-=qty; invalidate availability — 🔭 (event-driven Pass 2; TTL worker 🔭) |

## 10. Reliability
### Idempotency
- Commit idempotent: reservation đã COMMITTED → bỏ qua (không trừ 2 lần). Release idempotent. (🔭 khi wire consume.)
### Retry / Timeout / Circuit breaker
- Reserve Lua ~microsecond. Không CB (validate Event 🔭).
### Transaction boundaries
- Reserve: Lua atomic Redis + ghi reservation PG. Commit/Release: PG transaction + cập nhật Redis.
### Reconciliation / Durability
- Redis AOF (mất ≤1s khi crash). 🔭 Reconcile job `available = total - sold - active_reservations` (PG) — **chưa code** (no `@Scheduled`). Hiện chỉ M3 seed-if-missing rebuild stock key từ PG khi key vắng.
- ✅ Redis down → fallback Conditional UPDATE PG (`incrementReservedConditional`: `SET reserved=reserved+qty WHERE sold+reserved+qty<=total`) — `ReservationPersistence.writeReservationFallback` + `TicketTypeInventoryRepository.incrementReservedConditional`.

## 11. Cache (Redis) ✅ (`InventoryRedisService.java:29,32,35`)
| Key pattern | Data | TTL | Invalidation |
|---|---|---:|---|
| `tickefy:inventory:available:{ttId}` | Số vé còn (counter) | none (source counter) | reserve/commit/release cập nhật trực tiếp |
| `tickefy:inventory:meta:{ttId}` | meta (perUserLimit, price, sale window) | none | seed lúc tạo ticket type / M3 rebuild |
| `tickefy:inventory:user-limit:{userId}:{ttId}` | Quota đã sở hữu/user | none | reserve +, release − |
> Availability đọc **THẲNG counter** (`resolveAvailable` → seed-if-missing + GET, fallback PG; `TicketTypeService.java:103-126`) — **KHÔNG có lớp cache TTL riêng, KHÔNG có mutex/stampede lock** (claim cũ bỏ).

## 12. Security
- **Authentication:** JWT verify-only (public key auth). Reserve/commit/release = internal, Order gọi kèm `Authorization: Bearer` (service-to-service).
- **Authorization:** tạo ticket type = ORGANIZER/ADMIN (`@PreAuthorize`); availability = public; reservations = internal (Order).
- **Sensitive data:** không có dữ liệu nhạy đặc biệt.
- **Logging mask:** requestId; không secret.

## 13. Environment variables ✅ (theo `application.yml`)
| Variable | Required | Example | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ✅ | `docker` | Profile |
| `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD` | ✅ | postgres / `tickefy_inventory` | DB inventory (`application.yml:7-9`) |
| `DB_SCHEMA` | ✅ | `inventory_service` | Schema |
| `REDIS_HOST`/`REDIS_PORT` | ✅ | redis / 6379 | Lua counters + availability (`application.yml:28-29`) |
| `VALIDATE_CONCERT` | optional | `false` (default) | Bật validate concertId qua Event (`application.yml:60`) — hiện skip |
| `SPRING_RABBITMQ_*` | 🔭 | rabbitmq | Khi wire consume Pass 2 |
| `APP_DEV_SEED_ENABLED` | optional | `true` (chỉ dev) | Bật DevSeedRunner (seed 1 concert + 5 ticket type) |
| reservation TTL config | ⚠️ | `PT15M` | TTL giữ vé |

## 14. Observability
- **Logs:** requestId; reserve result (SUCCESS/SOLD_OUT/LIMIT_EXCEEDED).
- **Metrics:** actuator mặc định ✅; 🔭 custom counter (sold-out / limit-exceeded / reserve) chưa code.
- **Traces:** propagate X-Request-Id.
- **Alerts:** (không formal).

## 15. Failure scenarios
| Scenario | Expected behavior | Error/event |
|---|---|---|
| Hết vé khi reserve | Lua SOLD_OUT → 409 | `TICKET_SOLD_OUT` ✅ |
| Vượt per-user limit | Lua LIMIT_EXCEEDED → 422 (kèm remaining) | `PER_USER_LIMIT_EXCEEDED` ✅ |
| Ngoài giờ bán | 403 | `SALE_WINDOW_CLOSED` ✅ |
| Reservation quá 15' chưa thanh toán | Worker release, trả vé + quota | 🔭 `RESERVATION_EXPIRED` (410) — **chưa throw** (TTL worker Pass 2) |
| Payment failed / order expired | Release reservation | 🔭 Pass 2 (consume event) |
| OrderPaid gửi trùng | Idempotent — đã COMMITTED bỏ qua | 🔭 Pass 2 |
| Concert không tồn tại khi tạo ticket type | code throw `RESOURCE_NOT_FOUND` (404) | `RESOURCE_NOT_FOUND` ✅ (đã chốt) |
| ConcertCancelled | Ngừng bán khẩn cấp (khóa reservation mới) | 🔭 chờ Event |
| Redis crash | AOF khôi phục; 🔭 reconcile job từ PG chưa code (chỉ M3 seed-if-missing rebuild key) | 🔭 |
| Redis down hoàn toàn | Fallback Conditional UPDATE PG | ✅ (`incrementReservedConditional`) |
| ~~Cache availability stampede~~ | N/A — availability đọc thẳng counter, không cache TTL/mutex | — |

## 16. Integration acceptance criteria
- [ ] Health check pass.
- [x] Swagger available. ✅ (springdoc)
- [ ] API contract tests pass (ticket-type + availability + reserve).
- [ ] 🔭 Event contract tests — consume OrderPaid/etc CHƯA wire (Pass 2).
- [ ] Idempotent commit (OrderPaid ×3 → sold +1) — 🔭 Pass 2.
- [ ] Over-selling: 10k request / 200 vé → đúng 200, không 201 (AC1 inventory.md).
- [ ] Per-user limit không vượt dù song song (AC2).
- [ ] Docker image builds. · `.env.example` complete.
- [ ] 🔭 Gateway route — gateway chưa build.
- [ ] 🔭 Queue/binding/**DLQ** configured — Pass 2 (nhớ DLQ + requeue-false).
- [ ] Integration test Postgres + Redis pass.

## 17. Open questions
- ✅ Redis key (xác nhận): `tickefy:inventory:available:` / `:meta:` / `:user-limit:` (`InventoryRedisService.java:29,32,35`).
- ✅ Commit/release = **event-only** (cách B) — KHÔNG HTTP endpoint (đã chốt: §6/§8).
- ✅ Validate concertId qua Event: **skip** mặc định (`VALIDATE_CONCERT=false`); bật khi Event build.
- ✅ PG fallback đã code; 🔭 reconciliation job chưa code.
- ✅ INV-005 = `RESOURCE_NOT_FOUND` (đã chốt error-catalog).
- 🔭 Pass 2: wire consume OrderPaid/PaymentFailed/Expired + TTL worker + **DLQ + setDefaultRequeueRejected(false)**.
- Lock strategy: Redis+Lua (Hiệp) vs Pessimistic Lock (Hòa) — team chốt 1 cơ chế (không để 2 cùng chạy).
- Path ticket-type dưới `/events/` (trùng namespace Event) — giữ hay đổi?
