# checkin-service Performance Evidence

Timestamp: 2026-06-14 16:13:44 +07:00

## Final Run: Paced 1000-User Gate Flow

Status: PASS

Native `k6` was not available in PATH, so the load test was executed with Docker:

```powershell
docker run --rm --network local_tickefy-network `
  -v <evidence-performance>:/scripts `
  -e ADMIN_TOKEN=<redacted> `
  -e STAFF_TOKEN=<redacted> `
  -e ETICKET_BASE_URL=http://tickefy-e-ticket-service-evidence:8080 `
  -e CHECKIN_BASE_URL=http://tickefy-checkin-service-evidence:8080 `
  -e VUS=1000 `
  -e SEED_TICKETS=1000 `
  -e JITTER_SECONDS=20 `
  grafana/k6:latest run --summary-export /scripts/k6-paced-1000-summary-pool-45.json /scripts/k6-docker-checkin-paced-1000.js
```

Scenario:

- Seeded 1000 tickets through real `e-ticket-service` Docker API.
- Ran 1000 max VUs against real `checkin-service` Docker API.
- Each VU scanned its own QR twice, producing one valid scan and one duplicate scan.
- Services used real PostgreSQL 17 and real Docker network `local_tickefy-network`.
- Local PostgreSQL `max_connections` was raised to 220; both services used Hikari max pool size 45.

Result:

```text
iterations: 2000 complete, 0 interrupted
http_reqs: 3000
http_req_failed: 0.00%, 0 out of 3000
checks: 7000 passed, 0 failed
vus_max: 1000
http_req_duration: avg=16ms, p90=17.86ms, p95=25.13ms, max=703.37ms
k6 exit code: 0
```

Post-load DB verification:

```text
tickets: 1000
checkin_events: 2000
ACCEPTED: 1000
DUPLICATE_REJECTED: 1000
raw_qr_leak_rows: 0
```

## Stress Artifact: Same-Time 1000-VU Burst

Status: CORRECTNESS PASS / LATENCY LIMIT

The stricter burst script starts 1000 VUs with less pacing to model a worst-case local spike. After the atomic e-ticket check-in path, JWT verifier cache, JDK HTTP client, and JDBC audit insert optimizations, correctness stayed green but latency remained above the 5s threshold on this local Docker setup.

Latest burst result:

```text
iterations: 2000 complete, 0 interrupted
http_reqs: 3000
http_req_failed: 0.00%, 0 out of 3000
checks: 7000 passed, 0 failed
http_req_duration p95: 7.27s
threshold: p(95)<5000 failed
```

## Evidence Files

- `k6-docker-checkin-paced-1000.js`
- `k6-docker-paced-1000-pool-45.log`
- `k6-paced-1000-summary-pool-45.json`
- `post-k6-paced-1000-db-verification.log`
- `post-k6-paced-1000-docker-stats.log`
- `k6-docker-checkin-load.js`
- `k6-docker-load-after-qualified-jdbc-audit-table.log`
- `k6-scan-summary-after-qualified-jdbc-audit-table.json`
