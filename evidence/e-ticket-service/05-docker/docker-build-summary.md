# e-ticket-service Docker Build Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/e-ticket-service
docker build -t tickefy/e-ticket-service:evidence . 2>&1 | Tee-Object -FilePath ..\..\evidence\e-ticket-service\05-docker\docker-build.log
```

## Result

Status: PASS

Image produced: `tickefy/e-ticket-service:evidence`

The build completed without timeout.
