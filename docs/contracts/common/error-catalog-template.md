---
title: Error Catalog
status: DRAFT
version: 1.0
owner: BE Lead
reviewers: []
lastUpdated: YYYY-MM-DD
---

# Error Catalog

## 1. Quy tắc

- API code là string ổn định.
- Không branch logic bằng message.
- Internal ref chỉ dùng trong docs.
- Code không đổi ý nghĩa sau khi freeze.

## 2. Catalog

| Ref | Service | HTTP | Code | Message mặc định | Khi xảy ra | Client action |
|---|---|---:|---|---|---|---|
| ERR-COM-001 | common | 400 | `VALIDATION_ERROR` | Dữ liệu không hợp lệ. | | |
| ERR-COM-002 | common | 401 | `UNAUTHORIZED` | Chưa xác thực. | | |
| ERR-COM-003 | common | 403 | `FORBIDDEN` | Không đủ quyền. | | |
| ERR-COM-004 | common | 500 | `INTERNAL_SERVER_ERROR` | Lỗi hệ thống. | | |

## 3. Service-specific errors

Thêm từng section theo service.

### `<service-name>`

| Ref | HTTP | Code | Message | Details schema | Retryable |
|---|---:|---|---|---|---|
