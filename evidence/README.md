# Tickefy Backend Testing Evidence

Generated at: 2026-06-14 16:13:44 +07:00

Scope: `e-ticket-service` and `checkin-service` only.

## Result Summary

| Service | Unit/Integration | Real DB + REST Assured | Docker Build | Performance |
| --- | --- | --- | --- | --- |
| e-ticket-service | PASS, 26 tests | PASS, 12 IT/API tests | PASS build + PASS Docker runtime | N/A |
| checkin-service | PASS, 18 tests | PASS, 8 IT/API tests | PASS build + PASS Docker runtime smoke | PASS paced 1000-user Docker k6; strict same-time burst documented as capacity limit |

## Evidence Layout

```text
evidence/
  db-reset/
  e-ticket-service/
    01-unit-integration/
    02-real-db-testcontainers/
    03-api-rest-assured/
    04-security/
    05-docker/
  checkin-service/
    01-unit-integration/
    02-real-db-testcontainers/
    03-api-rest-assured/
    04-security/
    05-performance/
    06-docker/
```

## Commands Used

```powershell
docker exec tickefy-postgres psql -U tickefy -d tickefy

cd services/e-ticket-service
.\mvnw.cmd test
.\mvnw.cmd -Preal-db-test verify
docker build -t tickefy/e-ticket-service:evidence .

cd services/checkin-service
.\mvnw.cmd test
.\mvnw.cmd -Preal-db-test verify
docker build -t tickefy/checkin-service:evidence .

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

## Notes

- Local PostgreSQL schemas were reset before evidence collection.
- Applied Flyway migrations were not edited.
- Testcontainers used PostgreSQL `17-alpine`.
- Native `k6` was not available in PATH, so performance was run with Docker image `grafana/k6:latest`.
- Local PostgreSQL `max_connections` was raised to 220 for Docker evidence; both services ran with Hikari max pool size 45.
- The final paced 1000-user gate-flow run completed 2000 scan iterations with 0 HTTP failures, 7000/7000 checks, and `http_req_duration p95=25.13ms`.
- The stricter same-time 1000-VU burst remains documented as a local capacity limit: correctness passed with 0 HTTP failures and 0 failed checks, but p95 stayed above the 5s latency threshold.
