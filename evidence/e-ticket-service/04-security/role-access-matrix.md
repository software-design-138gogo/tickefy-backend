# e-ticket-service Role Access Matrix

Timestamp: 2026-06-14 14:30:43 +07:00

| Endpoint group | AUDIENCE | ADMIN/STAFF/internal |
| --- | --- | --- |
| Customer owned ticket lookup | Allowed for owner only | Allowed when endpoint role permits |
| Internal issue/by-token/snapshot/check-in | Rejected | Allowed |
| Missing/invalid token | Rejected | Rejected |

Evidence source: `TicketControllerSecurityTest`, `TicketApiIT`, and Maven logs in this evidence package.
