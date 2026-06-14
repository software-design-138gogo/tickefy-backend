# Tickefy Backend Testing Evidence

Generated at: 2026-06-14 14:30:43 +07:00

Scope: `e-ticket-service` and `checkin-service` only.

## Result Summary

| Service | Unit/Integration | Real DB + REST Assured | Docker Build | Performance |
| --- | --- | --- | --- | --- |
| e-ticket-service | PASS, 26 tests | PASS, 9 IT tests | PASS | N/A |
| checkin-service | PASS, 18 tests | PASS, 8 IT tests | PASS build + PASS Docker runtime smoke | RAN, 1000 VUs correctness pass; latency threshold failed |

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
```

## Notes

- Local PostgreSQL schemas were reset before evidence collection.
- Applied Flyway migrations were not edited.
- Testcontainers used PostgreSQL `17-alpine`.
- Native `k6` was not available in PATH, so performance was run with Docker image `grafana/k6:latest`.
- 1000-VU k6 run completed 2000 scan iterations with 0 HTTP failures and 0 failed checks, but failed the strict latency threshold `p(95)<5000` because measured p95 was 11410 ms.
