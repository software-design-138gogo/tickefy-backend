# e-ticket-service Unit/Integration Evidence

Timestamp: 2026-06-14 14:30:43 +07:00

## Command

```powershell
cd services/e-ticket-service
.\mvnw.cmd test 2>&1 | Tee-Object -FilePath ..\..\evidence\e-ticket-service\01-unit-integration\mvn-test.log
```

## Result

Status: PASS

```text
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Covered Suites

- `ETicketServiceApplicationTests`
- `OrderPaidConsumerTest`
- `TicketControllerSecurityTest`
- `TicketServiceTest`
