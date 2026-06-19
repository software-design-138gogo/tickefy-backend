# Tickefy AI Bio Service

Tickefy AI Bio Service for the Tickefy backend system.

# ai-bio-service

Python/FastAPI service for generating concert introductions from source documents.

## Local run

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8080 --reload
```

## Health

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/livez
curl http://localhost:8080/readyz
```

## Swagger

```
http://localhost:8080/swagger-ui/index.html
```