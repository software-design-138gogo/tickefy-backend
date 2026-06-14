# Hướng dẫn chạy & Môi trường (Runbook & Environment)

Status: ACTIVE
Last updated: 2026-06-13

Phạm vi: `e-ticket-service` và `checkin-service`.

## Biến môi trường (Environment variables)

| Biến | Ý nghĩa | Ví dụ Local Maven | Ví dụ Docker Network |
|---|---|---|---|
| `SERVER_PORT` | Port HTTP | `8087` / `8088` | `8087` / `8088` |
| `DB_HOST` | Host PostgreSQL | `localhost` | `tickefy-postgres` |
| `DB_PORT` | Port PostgreSQL | `5432` | `5432` |
| `DB_NAME` | Tên Database | `tickefy` | `tickefy` |
| `DB_USERNAME` | DB username | `tickefy` | `tickefy` |
| `DB_PASSWORD` | DB password | `change_me` | `change_me` |
| `DB_SCHEMA` | Schema của service | `ticket_schema` / `checkin_schema` | `ticket_schema` / `checkin_schema` |
| `JWT_SECRET` | Secret để validate JWT | dev secret | dev secret |
| `ETICKET_SERVICE_URL` | (Chỉ check-in) URL gọi e-ticket | `http://localhost:8087` | `http://tickefy-eticket-realapi:8087` |

## JWT Role Claims

Hệ thống chấp nhận các định dạng claim sau để tương thích:
```json
{"sub":"staff-1","roles":["CHECKIN_STAFF"]}
{"sub":"staff-1","roles":["ROLE_CHECKIN_STAFF"]}
{"sub":"staff-1","authorities":["ROLE_CHECKIN_STAFF"]}
{"sub":"staff-1","scope":"CHECKIN_STAFF"}
{"sub":"staff-1","role":"CHECKIN_STAFF"}
```

## Docker Guide

Cả hai service đều dùng multi-stage Dockerfile (Maven build -> JRE runtime với user `spring`).

### Build Image

```powershell
# Từ tickefy-backend/services/e-ticket-service
docker build -t tickefy/e-ticket-service:local .

# Từ tickefy-backend/services/checkin-service
docker build -t tickefy/checkin-service:local .
```

### Chạy với Real API & DB

Sử dụng script có sẵn để chạy cả 2 service kết nối với `tickefy-postgres`:

```powershell
cd tickefy-backend
.\scripts\test-with-backend-real-api.ps1
```

### Khi nào Build vs Pull Image

- **Sửa code local:** Build image local (`docker build`).
- **Test artifact từ CI:** Pull image từ registry (`docker pull`).
- **Demo bản stable:** Pull image tag stable.