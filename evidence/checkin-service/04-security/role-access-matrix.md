# checkin-service Role Access Matrix

Timestamp: 2026-06-14 14:30:43 +07:00

| Endpoint group | AUDIENCE | STAFF/ADMIN |
| --- | --- | --- |
| Scan ticket | Rejected | Allowed |
| Snapshot | Rejected unless role permits | Allowed |
| Offline sync | Rejected unless role permits | Allowed |
| Missing/invalid token | Rejected | Rejected |

Evidence source: `CheckinControllerSecurityTest`, `CheckinApiIT`, and Maven logs in this evidence package.
