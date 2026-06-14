# checkin-service Performance Evidence

Timestamp: 2026-06-14 14:47:19 +07:00

## Command

Native `k6` was not available in PATH, so the load test was executed with Docker:

```powershell
docker run --rm --network local_tickefy-network `
  -v <evidence-performance>:/scripts `
  -e ADMIN_TOKEN=<redacted> `
  -e STAFF_TOKEN=<redacted> `
  -e ETICKET_BASE_URL=http://tickefy-e-ticket-service-evidence:8080 `
  -e CHECKIN_BASE_URL=http://tickefy-checkin-service-evidence:8080 `
  -e VUS=1000 `
  -e ITERATIONS=2000 `
  -e SEED_TICKETS=1000 `
  -e MAX_DURATION=10m `
  grafana/k6:latest run --summary-export /scripts/k6-scan-summary.json /scripts/k6-docker-checkin-load.js
```

## Scenario

- Seeded 1000 tickets through real `e-ticket-service` Docker API.
- Ran 1000 max VUs against real `checkin-service` Docker API.
- Completed 2000 scan iterations, intentionally creating valid scans and duplicate scan pressure.
- Services used real PostgreSQL 17 and real Docker network `local_tickefy-network`.

## Result

Status: PARTIAL PASS / LATENCY FAIL

Correctness and availability passed:

```text
iterations: 2000 complete, 0 interrupted
http_reqs: 3000
http_req_failed: 0.00%, 0 out of 3000
checks: 7000 passed, 0 failed
vus_max: 1000
```

Latency threshold failed:

```text
threshold: http_req_duration p(95)<5000
actual: p(95)=11410 ms
k6 exit code: 99
```

## Post-load DB Verification

```text
tickets: 1001
checkin_events: 2002
ACCEPTED: 1001
DUPLICATE_REJECTED: 1001
raw_qr_leaks: 0
```

## Evidence Files

- `k6-docker-checkin-load.js`
- `k6-docker-load.log`
- `k6-scan-summary.json`
- `post-k6-db-verification.log`
- `post-k6-docker-stats.log`
