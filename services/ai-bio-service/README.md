# AI Bio Service

A Python/FastAPI microservice that generates concert introductions from organizer-provided source documents using AI. Part of the Tickefy backend system.

## Overview

`ai-bio-service` accepts source files (PDF, Markdown, TXT, DOCX, PPTX) uploaded by an Organizer or Admin, extracts text from them, builds a bounded context, and calls an AI provider (mock or OpenAI) to generate a concert introduction. The generated result is published as a `ConcertIntroductionGenerated` event via RabbitMQ, which `event-service` consumes and applies as the official public `concertIntroduction`.

### Key characteristics

- **Idempotent job creation** — each request requires an `Idempotency-Key` header to prevent duplicate jobs from network retries.
- **Asynchronous processing** — the API returns `202 Accepted` immediately; a background worker handles extraction, generation, and publishing.
- **Provider-agnostic** — supports a deterministic mock provider for local/dev and OpenAI (`gpt-5.4-mini`) for real generation.
- **JWT RS256 auth** — verifies access tokens independently (does not trust gateway headers).

### End-to-end flow

```
Client → API Gateway → ai-bio-service (create job + upload files)
  → event-service (validate concert via internal API)
  → Object Storage (store source files)
  → Worker (extract text → build context → call AI → validate output)
  → RabbitMQ (publish ConcertIntroductionGenerated)
  → event-service consumer (apply introduction to concert)
```

## Prerequisites

- **Python 3.12+**
- **PostgreSQL** — schema `ai_bio_schema`
- **MinIO / S3** — bucket `tickefy-ai-bio`
- **RabbitMQ** — exchange `tickefy.exchange`
- **RS256 public key** — for JWT verification (place at `keys/public.pem`)
- **event-service** running (or a mock) — for concert validation via `GET /internal/concerts/{concertId}/ai-context`

## Getting started

### 1. Setup environment

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. Configure

```bash
cp .env.example .env
# Edit .env with your local settings (DB, MinIO, RabbitMQ, etc.)
```

Key environment variables:

| Variable | Description | Example |
|---|---|---|
| `DB_HOST` / `DB_PORT` | PostgreSQL connection | `localhost` / `5432` |
| `DB_SCHEMA` | Database schema | `ai_bio_schema` |
| `OBJECT_STORAGE_ENDPOINT` | MinIO/S3 endpoint | `http://localhost:9000` |
| `RABBITMQ_HOST` | RabbitMQ host | `localhost` |
| `EVENT_SERVICE_URL` | Event service base URL | `http://localhost:8092` |
| `JWT_PUBLIC_KEY_PATH` | Path to RS256 public key | `/app/keys/public.pem` |
| `AI_PROVIDER` | `mock` or `openai` | `mock` |
| `OPENAI_API_KEY` | Required when `AI_PROVIDER=openai` | *(never commit)* |
| `WORKER_ENABLED` | Enable background job processing | `true` |
| `DEV_ENDPOINTS_ENABLED` | Enable dev/debug endpoints | `false` |

### 3. Run database migrations

```bash
alembic upgrade head
```

The Docker image runs `alembic upgrade head` automatically before starting Uvicorn.

### 4. Start the service

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8090 --reload
```

The service listens on port `8080` inside Docker, or `8090` for local debugging by convention.

## Running modes

| Mode | `WORKER_ENABLED` | `DEV_ENDPOINTS_ENABLED` | `AI_PROVIDER` | Use case |
|---|:---:|:---:|---|---|
| Manual debug | `false` | `true` | `mock` or `openai` | Step through pipeline manually via dev endpoints |
| Normal local Docker | `true` | `false` | `mock` | Standard demo with automatic processing |
| Real AI demo | `true` | `false` | `openai` | Demo with actual AI generation |
| Safe idle | `false` | `false` | `mock` | Service up but no processing |

## API summary

### Public APIs (via Gateway at `/api/ai-bio`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/ai-bio/concerts/{concertId}/jobs` | Create a generation job (multipart, requires `Idempotency-Key`) |
| `GET` | `/api/ai-bio/jobs/{jobId}` | Get job status and generated result |
| `GET` | `/api/ai-bio/concerts/{concertId}/jobs` | List jobs for a concert |
| `POST` | `/api/ai-bio/jobs/{jobId}/retry` | Retry a failed job |

### Dev-only APIs (requires `DEV_ENDPOINTS_ENABLED=true`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/ai-bio/_dev/jobs/{jobId}/extract` | Manually run extraction |
| `POST` | `/api/ai-bio/_dev/jobs/{jobId}/generate` | Manually run generation |
| `POST` | `/api/ai-bio/_dev/outbox/publish` | Manually publish pending outbox events |
| `POST` | `/api/ai-bio/_dev/jobs/{jobId}/run-pipeline` | Run full pipeline for one job |
| `POST` | `/api/ai-bio/_dev/jobs/run-next-pending` | Run pipeline for next pending job |

## Health checks

```bash
curl http://localhost:8090/health
curl http://localhost:8090/actuator/health
curl http://localhost:8090/livez
curl http://localhost:8090/readyz
```

## OpenAPI docs

```
http://localhost:8090/swagger-ui/index.html
http://localhost:8090/v3/api-docs
```

## Quick test (via Gateway)

```bash
# 1. Create a job
CONCERT_ID=<concert-id>
IDEMPOTENCY_KEY="ai-bio-test-$(date +%s)"

curl -X POST "http://localhost:8080/api/ai-bio/concerts/$CONCERT_ID/jobs" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -F "language=vi" \
  -F "targetLength=SHORT" \
  -F "tone=ENERGETIC" \
  -F "files=@/tmp/press-kit.txt;type=text/plain"

# 2. Check job status
JOB_ID=<job-id-from-response>

curl "http://localhost:8080/api/ai-bio/jobs/$JOB_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

## E2E verification

```bash
CONCERT_ID=<real-concert-id> RUN_MODE=worker ./scripts/verify-ai-bio-e2e.sh
```

## Further reading

- [Service specification](../../docs/contracts/services/ai-bio-service.md) — full API contract, data model, configuration reference, and error codes.
- [End-to-end flow](../../docs/contracts/flows/ai-bio-flow.md) — detailed sequence diagrams, state machine, and failure paths.
