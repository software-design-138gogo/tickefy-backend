# checkin-service — Performance Tests (k6)

> Last updated: 2026-06-18  
> Tool: k6 via Docker (`grafana/k6:latest`) — native k6 không có trong PATH  
> Scripts: `services/checkin-service/src/test/k6/`

## Paced 1000-User Gate Flow (Final Pass — 2026-06-14)

**Status: ✅ PASS**

### Setup

```powershell
# Chạy trong tickefy-infrastructure/local/
docker compose --env-file .env.example -f docker-compose.dev.yml up -d

# Seed 1000 tickets qua real e-ticket-service API (script riêng)
# Sau đó chạy k6
docker run --rm --network local_tickefy-network `
  -v "${PWD}/k6:/scripts" `
  -e ADMIN_TOKEN=<redacted> `
  -e STAFF_TOKEN=<redacted> `
  -e ETICKET_BASE_URL=http://tickefy-e-ticket-service:8080 `
  -e CHECKIN_BASE_URL=http://tickefy-checkin-service:8080 `
  -e VUS=1000 `
  -e SEED_TICKETS=1000 `
  -e JITTER_SECONDS=20 `
  grafana/k6:latest run `
    --summary-export /scripts/summary.json `
    /scripts/k6-checkin-paced-1000.js
```

### Scenario

- 1000 tickets seeded qua real `e-ticket-service` Docker API
- 1000 VUs, mỗi VU scan QR của mình 2 lần (1 valid + 1 duplicate)
- Real PostgreSQL 17, Docker network `local_tickefy-network`
- `max_connections=220`, Hikari pool size=45 per service

### Result

```text
iterations:        2000 complete, 0 interrupted
http_reqs:         3000
http_req_failed:   0.00% (0/3000)
checks:            7000 passed, 0 failed
vus_max:           1000
http_req_duration: avg=16ms  p90=17.86ms  p95=25.13ms  max=703.37ms
k6 exit code:      0
```

### Post-load DB Verification

```sql
-- Chạy trên tickefy-postgres container
SELECT COUNT(*) FROM checkin_service.checkin_events;           -- 2000
SELECT result, COUNT(*) FROM checkin_service.checkin_events 
  GROUP BY result;
-- ACCEPTED:          1000
-- DUPLICATE_REJECTED: 1000

SELECT COUNT(*) FROM checkin_service.checkin_events 
  WHERE qr_token IS NOT NULL;   -- 0 (raw QR không được lưu)
```

---

## Stress Artifact: Same-Time 1000-VU Burst

**Status: ⚠ CORRECTNESS PASS / LATENCY LIMIT (local capacity)**

Script `k6-checkin-burst.js` — 1000 VUs start đồng thời, ít pacing.

```text
iterations:        2000 complete, 0 interrupted
http_reqs:         3000
http_req_failed:   0.00% (0/3000)
checks:            7000 passed, 0 failed
http_req_duration p95: 7.27s
threshold:         p(95)<5000 — FAILED
```

**Kết luận:** Correctness đúng (0 failed checks, 0 duplicate scan được ACCEPT), latency vượt 5s threshold là giới hạn của local Docker setup, không phải bug logic.

---

## K6 Script Reference

| Script | Mục đích |
|---|---|
| `k6-checkin-paced-1000.js` | Main perf test: paced 1000 VUs, gate-flow scan |
| `k6-checkin-burst.js` | Stress: same-time 1000-VU burst |

### Thresholds (trong script)

```javascript
thresholds: {
  http_req_failed:   ['rate<0.01'],        // < 1% HTTP failure
  http_req_duration: ['p(95)<5000'],       // p95 < 5s
  checks:            ['rate>0.99'],        // > 99% check pass
}
```

## Chạy lại

```powershell
# Cần infrastructure đang chạy với real service containers
# Trong tickefy-infrastructure/local/
docker compose --env-file .env.example -f docker-compose.dev.yml up -d

# Chạy k6
docker run --rm --network local_tickefy-network `
  -v "D:/path/to/k6/scripts:/scripts" `
  grafana/k6:latest run /scripts/k6-checkin-paced-1000.js
```
