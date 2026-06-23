---
title: Service Specification - ai-bio-service
status: IMPLEMENTED_LOCAL
version: 3.0
owner: Hoàng
reviewers: [BE Lead, Event Service, API Gateway, Frontend]
lastUpdated: 2026-06-23
---

# Service Specification — `ai-bio-service`

## 1. Identity

| Item | Value |
|---|---|
| Service name | `ai-bio-service` |
| Owner | Hoàng |
| Repository | `tickefy-backend/services/ai-bio-service` |
| Runtime | Python 3.12 + FastAPI |
| Container port | `8080` |
| Local direct host port | `8089` when running by Docker Compose; `8090` when running direct local for debugging |
| Public base path | `/api/ai-bio` |
| Gateway route | `/api/ai-bio/**` → `http://ai-bio-service:8080` |
| Health check | `/health`, `/actuator/health`, `/livez`, `/readyz` |
| OpenAPI | `/swagger-ui/index.html`, `/v3/api-docs` |
| Database schema | `ai_bio_schema` |
| Object Storage | MinIO/S3 private bucket, default `tickefy-ai-bio` |
| RabbitMQ exchange | `tickefy.exchange` |
| Published routing key | `concert.introduction.generated` |

## 2. Current implementation status

`ai-bio-service` has been implemented locally with the following features:

| Area | Status | Notes |
|---|---|---|
| FastAPI skeleton, health, common envelope | Done | Compatible with project API Standard. |
| JWT RS256 verification | Done | Verifies `iss`, `aud`, `exp`, `sub`; does not trust `X-User-*`. |
| Organizer/Admin guard | Done | Create/retry/dev operations require Organizer/Admin. |
| API Gateway route | Done | `/api/ai-bio/**` works through Gateway port `8080`. |
| Event Service ai-context integration | Done | Calls `GET /internal/concerts/{concertId}/ai-context`. |
| Source validation | Done | Phase 1: PDF, MD, TXT, DOCX, PPTX. |
| Object Storage upload | Done | Stores private objects under generated object keys. |
| Job creation with idempotency | Done | `Idempotency-Key` required. Replay-safe. |
| Document extraction | Done | Extracts text and stores `document_extractions`. |
| Mock AI provider | Done | Default for local/dev. |
| OpenAI provider | Done | Tested with `gpt-5.4-mini`. |
| Outbox event creation | Done | Creates `ConcertIntroductionGenerated`. |
| RabbitMQ publisher | Done | Publishes pending outbox events and marks `PUBLISHED`. |
| Background worker | Done | Can process pending jobs automatically when enabled. |
| Retry flow | Done | Retryable failed jobs can be reset to `PENDING`. |
| Public job APIs | Done | Get job status and list jobs by concert. |
| Event Service consumer | Done | Applies generated introduction into Event Service DB. |
| Final E2E script | Done | Script verifies Gateway → AI Bio → Event Service → RabbitMQ → DB. |

## 3. Responsibilities

### Service is responsible for

- Accepting source documents for concert introduction generation.
- Creating idempotent AI Bio jobs for a concert.
- Verifying JWT access tokens with RS256 and applying role/ownership checks.
- Calling `event-service` to validate concert existence, owner, status and introduction timestamps.
- Validating uploaded files by count, extension, size, MIME hints and magic bytes.
- Uploading source files to private Object Storage.
- Extracting text from supported Phase 1 file formats.
- Cleaning extracted text and building a bounded context for the AI provider.
- Generating a concert introduction using a provider interface.
- Supporting both mock provider and real OpenAI provider.
- Persisting job status, stage, result, attempts, source metadata, extraction metadata and retry state.
- Creating `ConcertIntroductionGenerated` outbox events.
- Publishing outbox events to RabbitMQ.
- Providing job status/list/retry APIs for Organizer/Admin workflows.
- Running an optional background worker for pending jobs.

### Service is not responsible for

- Owning official concert data.
- Directly updating `event_service` database tables.
- Serving public concert detail pages.
- Deciding final public concert lifecycle/status.
- Crawling arbitrary URLs without Phase 2 SSRF controls.
- Processing unsupported files such as executable/archive/video/audio formats.
- Logging raw source documents, prompts, AI provider raw response bodies, JWTs or API keys.

## 4. Supported input sources

### Phase 1 — implemented

| Source type | Extensions | Validation | Extraction |
|---|---|---|---|
| PDF | `.pdf` | Magic bytes `%PDF-`; password-protected PDF rejected during extraction | `pypdf` |
| Markdown | `.md`, `.markdown` | UTF-8 text | Markdown to text via parser/BeautifulSoup |
| Text | `.txt` | UTF-8 text | Direct UTF-8 decode |
| Word | `.docx` | Office Open XML ZIP structure containing `word/` | `python-docx` |
| PowerPoint | `.pptx` | Office Open XML ZIP structure containing `ppt/` | `python-pptx` |

### Phase 2 — not enabled yet

| Source type | Plan | Required controls |
|---|---|---|
| Image | OCR or vision model extraction | Pixel/size limit, no image content logging, OCR failure handling |
| URL | Fetch HTML/text/PDF/DOCX/PPTX snapshot | HTTPS only, SSRF protection, private IP block, redirect limit, download limit, content-type allowlist |

### Upload limits

| Limit | Default |
|---|---:|
| Max files per job | 5 |
| Max uploaded file size | 10 MB/file |
| Max total uploaded size | 25 MB/job |
| Max AI context chars | 12,000 |
| Min AI output chars | 80 |
| Max AI output chars | 1,200 |

## 5. Data ownership

### Tables owned by `ai-bio-service`

| Table | Purpose |
|---|---|
| `concert_introduction_jobs` | Main job table: concert snapshot, actor, status, stage, retry, generated result, provider info. |
| `source_documents` | Source metadata: type, object key, original filename, checksum, extraction status and warnings. |
| `document_extractions` | Extracted and cleaned text, parser metadata and warning metadata. |
| `job_attempts` | Attempt history: attempt number, provider, model, duration and safe error info. |
| `outbox_events` | Transactional outbox for integration events. |
| `idempotency_records` | Replay-safe records for create-job requests. |

### Cross-service references

| Field | Source service | Validation strategy |
|---|---|---|
| `concert_id` | `event-service` | Validated through `GET /internal/concerts/{concertId}/ai-context`. |
| `concert_name_snapshot` | `event-service` | Stored when the job is created. |
| `organizer_id_snapshot` | `event-service` | Used for owner/admin checks and job query access. |
| `created_by` | `auth-service` / JWT | From verified JWT `sub`. |
| `created_by_role` | JWT roles | Resolved from verified roles. |
| `correlation_id` | Gateway/caller | From `X-Request-ID`, generated if missing. |

### Invariants

- No cross-service foreign keys.
- Other services must not query `ai_bio_schema` directly.
- Each job belongs to one concert.
- A job must have at least one valid source document.
- A concert can have at most one active job with status `PENDING` or `PROCESSING`.
- `SUCCEEDED` requires both generated result and outbox event creation.
- A `SUCCEEDED` job cannot be retried.
- Retry is allowed only for `FAILED` jobs with `is_retryable=true` and `retry_count < max_retries`.
- `Idempotency-Key` replay with the same request returns the same response.
- Reusing the same `Idempotency-Key` with a different request returns conflict.

## 6. Dependencies

### Synchronous dependencies

| Dependency | Endpoint/API | Purpose | Runtime URL examples |
|---|---|---|---|
| `auth-service` | JWT public key contract | JWT verification through RS256 public key | `JWT_PUBLIC_KEY_PATH=/app/keys/public.pem` |
| `event-service` | `GET /internal/concerts/{concertId}/ai-context` | Validate concert, owner, status and introduction timestamps | Docker: `http://event-service:8080`; Local mock: `http://localhost:8092` |
| OpenAI | Responses API | Real AI generation when `AI_PROVIDER=openai` | Model tested: `gpt-5.4-mini` |

### Infrastructure dependencies

| Dependency | Purpose | Docker URL |
|---|---|---|
| PostgreSQL | Jobs, sources, extractions, attempts, idempotency, outbox | `postgres:5432` |
| MinIO/S3 | Private source file storage | `http://minio:9000` |
| RabbitMQ | Publish `ConcertIntroductionGenerated` | `rabbitmq:5672` |
| Redis | Optional future lock/cache support | `redis:6379` |

## 7. Configuration

### Core runtime

| Env | Example | Notes |
|---|---|---|
| `SERVER_PORT` | `8080` | Container port. |
| `APP_ENV` | `local` | Environment label. |
| `DEV_ENDPOINTS_ENABLED` | `false` | Should be false for normal Docker run. |
| `WORKER_ENABLED` | `true` or `false` | Enables background job processing. |
| `WORKER_POLL_INTERVAL_SECONDS` | `5` | Worker polling interval. |
| `WORKER_BATCH_SIZE` | `1` | Number of pending jobs processed per loop. |

### Auth

| Env | Example |
|---|---|
| `JWT_ISSUER` | `tickefy-auth-service` |
| `JWT_AUDIENCE` | `tickefy-api` |
| `JWT_PUBLIC_KEY_PATH` | `/app/keys/public.pem` |
| `JWT_ALGORITHM` | `RS256` |
| `JWT_LEEWAY_SECONDS` | `30` |

### Database

| Env | Docker example | Local direct example |
|---|---|---|
| `DB_HOST` | `postgres` | `localhost` |
| `DB_PORT` | `5432` | `5432` |
| `DB_NAME` | `tickefy` | `tickefy` |
| `DB_USERNAME` | `tickefy` | `tickefy` |
| `DB_PASSWORD` | `tickefy` | `tickefy` |
| `DB_SCHEMA` | `ai_bio_schema` | `ai_bio_schema` |

### Event Service

| Env | Docker example | Local mock example |
|---|---|---|
| `EVENT_SERVICE_URL` | `http://event-service:8080` | `http://localhost:8092` |
| `EVENT_SERVICE_CONNECT_TIMEOUT_SECONDS` | `2` | `2` |
| `EVENT_SERVICE_READ_TIMEOUT_SECONDS` | `3` | `3` |
| `EVENT_SERVICE_MAX_ATTEMPTS` | `2` | `2` |

### Object Storage

| Env | Docker example | Local direct example |
|---|---|---|
| `OBJECT_STORAGE_ENDPOINT` | `http://minio:9000` | `http://localhost:9000` |
| `OBJECT_STORAGE_BUCKET_AI_BIO` | `tickefy-ai-bio` | `tickefy-ai-bio` |
| `OBJECT_STORAGE_ACCESS_KEY` | `minioadmin` | `minioadmin` |
| `OBJECT_STORAGE_SECRET_KEY` | `minioadmin` | `minioadmin` |

### RabbitMQ

| Env | Docker example | Local direct example |
|---|---|---|
| `RABBITMQ_HOST` | `rabbitmq` | `localhost` |
| `RABBITMQ_PORT` | `5672` | `5672` |
| `RABBITMQ_USERNAME` | `tickefy` | `tickefy` |
| `RABBITMQ_PASSWORD` | `tickefy` | `tickefy` |
| `RABBITMQ_EXCHANGE` | `tickefy.exchange` | `tickefy.exchange` |
| `RABBITMQ_DLX` | `tickefy.dlx` | `tickefy.dlx` |

### AI provider

| Env | Recommended default | Notes |
|---|---|---|
| `AI_PROVIDER` | `mock` | Use `openai` only when API key is provided. |
| `AI_MODEL` | `mock-concert-introduction-v1` | Used by mock provider. |
| `OPENAI_API_KEY` | empty | Must never be committed. |
| `OPENAI_MODEL` | `gpt-5.4-mini` | Tested successfully in local setup. |
| `OPENAI_TIMEOUT_SECONDS` | `30` | Prevent long-running worker hangs. |
| `OPENAI_MAX_RETRIES` | `0` or `1` | Keep low for predictable latency/cost. |
| `OPENAI_MAX_OUTPUT_TOKENS` | `500` | Cost/output control. |

## 8. Public APIs

All APIs return the project common envelope:

```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req-...",
  "timestamp": "2026-06-23T00:00:00Z"
}
```

### `POST /api/ai-bio/concerts/{concertId}/jobs`

Creates an idempotent background generation job.

| Item | Value |
|---|---|
| Role | `ORGANIZER`, `ADMIN` |
| Content type | `multipart/form-data` |
| Required headers | `Authorization`, `Idempotency-Key` |
| Response | `202 Accepted` |

Multipart fields:

| Field | Required | Notes |
|---|---:|---|
| `files` | Yes in Phase 1 | 1–5 files; allowed: PDF/MD/TXT/DOCX/PPTX. |
| `language` | No | Default `vi`; allowed `vi`, `en`. |
| `targetLength` | No | `SHORT`, `MEDIUM`, `LONG`. |
| `tone` | No | `PROFESSIONAL`, `ENERGETIC`, `LUXURY`, `FRIENDLY`. |

Response example:

```json
{
  "success": true,
  "data": {
    "jobId": "ef1a3bac-5c28-4ae1-a472-853f7783469f",
    "concertId": "2c346f70-d5b2-4e47-9456-dac09d909f8f",
    "status": "PENDING",
    "processingStage": "RECEIVED",
    "replayDetected": false,
    "sourceCount": 2,
    "language": "vi",
    "targetLength": "SHORT",
    "tone": "ENERGETIC",
    "createdAt": "2026-06-23T09:19:00Z"
  },
  "error": null,
  "requestId": "req-...",
  "timestamp": "2026-06-23T09:19:00Z"
}
```

### `GET /api/ai-bio/jobs/{jobId}`

Returns safe job status and generated candidate. It does not return raw source text, cleaned text, prompt or provider raw response.

| Item | Value |
|---|---|
| Role | Authenticated owner/admin |
| Response | `200 OK` |

Important fields:

```json
{
  "jobId": "...",
  "status": "SUCCEEDED",
  "processingStage": "COMPLETED",
  "generatedIntroduction": "...",
  "providerName": "openai",
  "providerModel": "gpt-5.4-mini",
  "sources": [
    {
      "sourceDocumentId": "...",
      "sourceType": "TEXT",
      "status": "EXTRACTED",
      "originalFileName": "press-kit.txt"
    }
  ]
}
```

### `GET /api/ai-bio/concerts/{concertId}/jobs`

Lists AI Bio job history for a concert.

| Query | Default |
|---|---:|
| `limit` | 20 |
| `offset` | 0 |

### `POST /api/ai-bio/jobs/{jobId}/retry`

Retries a failed retryable job.

Rules:

- Only `FAILED` jobs can be retried.
- Job must have `is_retryable=true`.
- `retry_count < max_retries`.
- Retry resets job to `PENDING/RECEIVED`.
- Background worker or dev pipeline can process it again.

## 9. Development-only APIs

All `_dev/*` endpoints are guarded by `DEV_ENDPOINTS_ENABLED`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/ai-bio/_me` | Check verified user identity. Not dev-only. |
| `GET` | `/api/ai-bio/_organizer-check` | Check Organizer/Admin guard. Not dev-only. |
| `POST` | `/api/ai-bio/_dev/jobs/{jobId}/extract` | Manually run extraction. |
| `POST` | `/api/ai-bio/_dev/jobs/{jobId}/generate` | Manually run generation. |
| `POST` | `/api/ai-bio/_dev/outbox/publish` | Manually publish pending outbox. |
| `POST` | `/api/ai-bio/_dev/jobs/{jobId}/run-pipeline` | Run extract → generate → publish for one job. |
| `POST` | `/api/ai-bio/_dev/jobs/run-next-pending` | Run pipeline for the next pending job. |

Recommended defaults:

| Mode | `DEV_ENDPOINTS_ENABLED` | `WORKER_ENABLED` | `AI_PROVIDER` |
|---|---:|---:|---|
| Manual local debug | `true` | `false` | `mock` or `openai` |
| Normal local Docker demo | `false` | `true` | `mock` |
| Real AI demo | `false` | `true` | `openai` |
| Safer idle mode | `false` | `false` | `mock` |

## 10. Job lifecycle

```text
RECEIVED
-> EXTRACTING_TEXT
-> CLEANING_TEXT
-> BUILDING_CONTEXT
-> CALLING_AI
-> VALIDATING_OUTPUT
-> PUBLISHING_RESULT
-> COMPLETED
```

Status transitions:

| From | To | Trigger |
|---|---|---|
| none | `PENDING` | Create job succeeds. |
| `PENDING` | `PROCESSING` | Worker/dev pipeline claims job. |
| `PROCESSING` | `SUCCEEDED` | Introduction saved and outbox event created. |
| `PROCESSING` | `FAILED` | Extraction/provider/output/storage failure. |
| `FAILED` | `PENDING` | Retry accepted. |
| `SUCCEEDED` | unchanged | Retry rejected. Regenerate requires new job. |

## 11. AI provider behavior

Provider selection:

```text
AI_PROVIDER=mock   -> local deterministic mock provider
AI_PROVIDER=openai -> OpenAI provider
```

Current tested provider:

| Provider | Model | Status |
|---|---|---|
| `mock` | `mock-concert-introduction-v1` | Works locally and in Docker. |
| `openai` | `gpt-5.4-mini` | Tested successfully. |
| `openai` | `gpt-5.5` | Failed in current local setup; not recommended as default. |

Provider safety rules:

- Do not log prompt text.
- Do not log source document content.
- Do not log provider raw response body.
- Store only final validated introduction and safe error metadata.
- Keep context bounded by `AI_MAX_CONTEXT_CHARS`.
- Keep output bounded by `OPENAI_MAX_OUTPUT_TOKENS` and `AI_MAX_OUTPUT_CHARS`.

## 12. Events published

### `ConcertIntroductionGenerated`

| Item | Value |
|---|---|
| Exchange | `tickefy.exchange` |
| Routing key | `concert.introduction.generated` |
| Producer | `ai-bio-service` |
| Consumer | `event-service` |
| Outbox table | `ai_bio_schema.outbox_events` |

Envelope example:

```json
{
  "messageId": "c7dab804-e058-413d-bed3-f6886d6609e1",
  "eventType": "ConcertIntroductionGenerated",
  "eventVersion": "1.0",
  "source": "ai-bio-service",
  "occurredAt": "2026-06-23T09:19:19Z",
  "correlationId": "req-ai-bio-real-ai-run-local-new-001",
  "causationId": null,
  "payload": {
    "jobId": "ef1a3bac-5c28-4ae1-a472-853f7783469f",
    "concertId": "2c346f70-d5b2-4e47-9456-dac09d909f8f",
    "introduction": "...",
    "language": "vi",
    "sourceDocumentIds": ["..."],
    "sourceTypes": ["TEXT", "MARKDOWN"],
    "requestedAt": "2026-06-23T09:19:00Z",
    "generatedAt": "2026-06-23T09:19:19Z"
  }
}
```

## 13. Event Service integration

`event-service` owns the official public `concertIntroduction`.

### Internal API consumed by AI Bio

```http
GET /internal/concerts/{concertId}/ai-context
Authorization: Bearer <access-token>
X-Request-ID: <request-id>
```

Expected response:

```json
{
  "concertId": "...",
  "concertName": "...",
  "organizerId": "...",
  "status": "DRAFT",
  "currentIntroductionUpdatedAt": null,
  "manualIntroductionUpdatedAt": null
}
```

### Event consumer behavior

`event-service` consumes `ConcertIntroductionGenerated` from:

| Item | Value |
|---|---|
| Queue | `event-service.concert-introduction-generated.queue` |
| Binding exchange | `tickefy.exchange` |
| Binding routing key | `concert.introduction.generated` |
| DLX | `tickefy.dlx` |
| DLQ | `event-service.concert-introduction-generated.queue.dlq` |

Consumer rules:

- Deduplicate by `messageId` in `event_service.processed_messages`.
- Validate `eventType=ConcertIntroductionGenerated` and `eventVersion=1.0`.
- Validate `source=ai-bio-service`.
- Apply introduction to `event_service.concerts.concert_introduction`.
- Store `concert_introduction_source_job_id`, language and update time.
- Do not overwrite a newer manual introduction if `manual_introduction_updated_at > payload.requestedAt`.
- Invalidate concert detail cache after applying.

## 14. API Gateway integration

Gateway route:

```yaml
- id: ai-bio-upload-route
  uri: ${AI_BIO_SERVICE_URL:http://ai-bio-service:8080}
  predicates:
    - Path=/api/ai-bio/concerts/*/jobs
    - Method=POST
  metadata:
    connect-timeout: 3000
    response-timeout: 60000

- id: ai-bio-service-route
  uri: ${AI_BIO_SERVICE_URL:http://ai-bio-service:8080}
  predicates:
    - Path=/api/ai-bio/**
  metadata:
    connect-timeout: 3000
    response-timeout: 15000
```

Required forwarded headers:

```text
Authorization
X-Request-ID
Idempotency-Key
Content-Type
Accept
```

No path rewrite is needed because AI Bio already serves `/api/ai-bio/**`.

## 15. Error codes

| Code | HTTP | Meaning |
|---|---:|---|
| `SOURCE_REQUIRED` | 400 | No source file provided. |
| `UNSUPPORTED_SOURCE_TYPE` | 415 | File/source type not supported. |
| `INVALID_SOURCE_TYPE` | 415 | Extension/MIME/magic bytes/parser validation failed. |
| `SOURCE_TOO_LARGE` | 413 | File or total payload too large. |
| `CONCERT_NOT_FOUND` | 404 | Event Service cannot find concert. |
| `CONCERT_ACCESS_DENIED` | 403 | User cannot manage/access the concert/job. |
| `EVENT_SERVICE_UNAVAILABLE` | 503 | Event Service is down/timeout/invalid contract. |
| `AI_BIO_JOB_ALREADY_ACTIVE` | 409 | Concert already has active job. |
| `AI_BIO_JOB_NOT_RETRYABLE` | 409 | Retry is not allowed. |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Create job missing idempotency key. |
| `IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST` | 409 | Same key used for different request. |
| `NO_USABLE_SOURCE_CONTENT` | 409/422 | Extraction produced no usable content. |
| `DOCUMENT_PASSWORD_PROTECTED` | 422 | PDF is encrypted/password-protected. |
| `OBJECT_STORAGE_UNAVAILABLE` | 503 | MinIO/S3 unavailable. |
| `AI_PROVIDER_UNAVAILABLE` | 503 | AI provider key/quota/rate-limit/network unavailable. |
| `AI_PROVIDER_INVALID_RESPONSE` | 503 | Provider returned empty/invalid response. |
| `AI_OUTPUT_INVALID` | 409/422 | Output failed validation rules. |

## 16. Observability

Recommended log fields:

```text
requestId, correlationId, jobId, concertId, sourceDocumentId,
messageId, eventType, status, processingStage, providerName,
providerModel, durationMs, retryCount, errorCode
```

Do not log:

```text
JWT, API key, source file content, extracted_text, cleaned_text, prompt, provider raw body
```

Recommended metrics:

| Metric | Meaning |
|---|---|
| `ai_bio_jobs_total{status}` | Job count by status. |
| `ai_bio_job_duration_seconds` | Total job processing duration. |
| `ai_bio_stage_duration_seconds{stage}` | Stage duration. |
| `ai_bio_source_extraction_failures_total{sourceType,code}` | Extraction failure count. |
| `ai_bio_provider_requests_total{provider,result}` | AI provider request count. |
| `ai_bio_provider_latency_seconds{provider}` | AI provider latency. |
| `ai_bio_outbox_pending_total` | Pending outbox count. |
| `ai_bio_outbox_publish_failures_total` | Outbox publish failures. |

## 17. Final verification

Final verification script:

```bash
CONCERT_ID=<real-event-service-concert-id> \
RUN_MODE=worker \
./scripts/verify-ai-bio-e2e.sh
```

Expected result:

```text
AI Bio E2E verification passed.
JOB_ID=...
CONCERT_ID=...
IDEMPOTENCY_KEY=...
```

The script verifies:

- Gateway route works.
- Auth token works.
- AI Bio calls real Event Service AI context API.
- Source upload works.
- Worker processes the job.
- AI provider generates introduction.
- Outbox event is created and published.
- Event Service consumes event and updates `concert_introduction`.

## 18. Remaining items

| Item | Status | Owner |
|---|---|---|
| Public Event Service DTO exposes `concertIntroduction` | Check/update if not exposed | Event Service |
| Frontend upload UI | Pending | Frontend |
| Frontend job status polling | Pending | Frontend |
| Phase 2 URL/image sources | Future | AI Bio |
| Production secrets management for OpenAI key | Future | DevOps/BE |
| Production monitoring dashboard | Future | BE/DevOps |
