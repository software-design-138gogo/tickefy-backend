# checkin-service Docker Build Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/checkin-service
docker build -t tickefy/checkin-service:evidence . 2>&1 | Tee-Object -FilePath ..\..\evidence\checkin-service\06-docker\docker-build.log
```

## Result

Status: PASS

Image produced: `tickefy/checkin-service:evidence`

The build completed without timeout.
