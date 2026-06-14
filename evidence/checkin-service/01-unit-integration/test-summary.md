# checkin-service Unit/Integration Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/checkin-service
.\mvnw.cmd test 2>&1 | Tee-Object -FilePath ..\..\evidence\checkin-service\01-unit-integration\mvn-test.log
```

## Result

Status: PASS

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Covered Suites

- `CheckinServiceApplicationTests`
- `CheckinControllerSecurityTest`
- `CheckinServiceTest`
