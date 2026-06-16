---
title: Check-in Result Catalog
status: ACCEPTED
version: 1.0
owner: Hòa (ticket/checkin)
reviewers: [BE Lead, Mobile]
lastUpdated: 2026-06-16
---

# Check-in Result Catalog — Ticket / Check-in

> Catalog này định nghĩa **business result codes** cho online/offline check-in. Đây KHÔNG phải `error.code` trong error envelope. API error vẫn dùng `./error-catalog.md`; response format chung ở `./api-standard.md`.

## 1. Nguyên tắc

- Expected scan rejection là kết quả nghiệp vụ đã xử lý được, trả `HTTP 200` + `success=true` + `data.result`.
- Auth, permission, validation, dependency và system failure vẫn trả `success=false` + `error.code` theo `./error-catalog.md`.
- Mobile branch bằng `data.result`, không parse `message`.
- Result code dùng `UPPER_SNAKE_CASE` và không đổi ý nghĩa sau khi contract freeze.
- Không trả hoặc log raw `qrToken`; dùng `qrTokenMasked`, `ticketId`, `concertId`, `deviceId`, `gate`, `staffId` để trace.

## 2. Online check-in result codes

| Result code | Meaning | Mobile UX | Audit requirement |
|---|---|---|---|
| `ACCEPTED` | Vé hợp lệ và đã đổi trạng thái sang `CHECKED_IN` | Màn hình xanh / cho vào | Ghi `ticketId`, `concertId`, `staffId`, `gate`, `checkedInAt` |
| `DUPLICATE_REJECTED` | Vé đã được check-in trước đó | Màn hình đỏ / cảnh báo vé đã dùng | Ghi lần scan hiện tại và `firstCheckedInAt` nếu có |
| `WRONG_EVENT` | Vé không thuộc `concertId` đang scan | Màn hình đỏ / sai sự kiện | Ghi `scannedConcertId`, `ticketConcertId` nếu có |
| `CANCELLED_REJECTED` | Vé đã bị hủy do concert/order/ticket cancellation | Màn hình đỏ / vé đã hủy | Ghi ticket status hiện tại |
| `REFUNDED_REJECTED` | Vé đã refund, không còn hợp lệ | Màn hình đỏ / vé đã hoàn tiền | Ghi ticket status hiện tại |
| `INVALID_QR_REJECTED` | QR parse được request nhưng không khớp vé hợp lệ | Màn hình đỏ / QR không hợp lệ | Chỉ log masked token/hash |

## 3. Offline local result codes

| Result code | Meaning | Mobile behavior |
|---|---|---|
| `OFFLINE_ACCEPTED_PENDING_SYNC` | QR khớp snapshot local và chưa scan trên thiết bị hiện tại | Cho vào tạm thời, đưa item vào local sync queue |
| `OFFLINE_DUPLICATE_LOCAL` | QR đã scan trước đó trên cùng thiết bị/snapshot | Từ chối local, không enqueue item mới |
| `OFFLINE_NOT_IN_SNAPSHOT` | QR không có trong snapshot local | Từ chối local, đánh dấu cần xử lý thủ công |
| `OFFLINE_SNAPSHOT_EXPIRED` | Snapshot local quá hạn | Không cho scan offline, yêu cầu tải snapshot mới khi có mạng |

## 4. Offline sync item result codes

| Result code | Meaning | Server behavior |
|---|---|---|
| `SYNC_ACCEPTED` | Item offline được server chấp nhận và ticket đã `CHECKED_IN` | Lưu audit + item result |
| `SYNC_DUPLICATE_REJECTED` | Ticket đã check-in trước khi item sync lên | Lưu conflict/audit |
| `SYNC_WRONG_EVENT` | Item thuộc concert khác | Reject item, ghi audit |
| `SYNC_CANCELLED_REJECTED` | Ticket đã cancelled | Reject item, ghi audit |
| `SYNC_REFUNDED_REJECTED` | Ticket đã refunded | Reject item, ghi audit |
| `SYNC_CONFLICT` | Có conflict cần BTC/staff xử lý sau | Lưu conflict record |
| `SYNC_ITEM_INVALID` | Một item trong batch sai schema/thiếu field | Reject item, không fail toàn batch nếu batch còn item hợp lệ |

## 5. Batch-level result codes

| Result code | Meaning | Replay behavior |
|---|---|---|
| `SYNC_BATCH_ACCEPTED` | Toàn bộ item hợp lệ và đã xử lý | Replay cùng `syncBatchId` trả response cũ |
| `SYNC_BATCH_COMPLETED_WITH_CONFLICTS` | Batch xử lý xong nhưng có item conflict/rejected | Replay cùng `syncBatchId` trả response cũ |
| `SYNC_BATCH_REPLAYED` | Request là replay của batch đã xử lý | Không xử lý lại item |
| `SYNC_BATCH_PARTIAL_FAILED` | Một phần batch lỗi tạm thời, có thể retry an toàn | Retry giữ nguyên `syncBatchId` |

## 6. Canonical online response shape

```json
{
  "success": true,
  "data": {
    "result": "ACCEPTED",
    "ticketId": "ticket-uuid",
    "concertId": "concert-uuid",
    "ticketTypeName": "SVIP",
    "gate": "GATE_A",
    "checkedInAt": "2026-06-16T10:00:00Z",
    "replayDetected": false,
    "message": "Check-in thành công."
  },
  "error": null,
  "requestId": "req-uuid",
  "timestamp": "2026-06-16T10:00:00Z"
}
```

## 7. Canonical offline sync response shape

```json
{
  "success": true,
  "data": {
    "syncBatchId": "sync-batch-uuid",
    "result": "SYNC_BATCH_COMPLETED_WITH_CONFLICTS",
    "concertId": "concert-uuid",
    "deviceId": "device-uuid",
    "totalItems": 3,
    "acceptedCount": 1,
    "rejectedCount": 1,
    "conflictCount": 1,
    "replayDetected": false,
    "items": [
      {
        "offlineScanId": "offline-scan-1",
        "ticketId": "ticket-uuid-1",
        "result": "SYNC_ACCEPTED",
        "checkedInAt": "2026-06-16T10:00:00Z"
      },
      {
        "offlineScanId": "offline-scan-2",
        "ticketId": "ticket-uuid-2",
        "result": "SYNC_DUPLICATE_REJECTED",
        "firstCheckedInAt": "2026-06-16T09:59:00Z"
      },
      {
        "offlineScanId": "offline-scan-3",
        "ticketId": "ticket-uuid-3",
        "result": "SYNC_CONFLICT",
        "conflictId": "conflict-uuid"
      }
    ]
  },
  "error": null,
  "requestId": "req-uuid",
  "timestamp": "2026-06-16T10:00:05Z"
}
```

## 8. Mapping to API errors

| Situation | Use result code? | Use error catalog? |
|---|---:|---:|
| Staff scan duplicate valid request | Yes | No |
| Wrong concert valid request | Yes | No |
| QR string malformed / missing required field | No | `VALIDATION_ERROR` or `INVALID_QR_TOKEN` |
| Staff token missing/expired | No | `UNAUTHORIZED` / `INVALID_TOKEN` |
| Staff lacks role | No | `FORBIDDEN` |
| Ticket Service unavailable | No | `SERVICE_UNAVAILABLE` |
| Batch payload too large | No | `SYNC_BATCH_TOO_LARGE` |
| Snapshot expired before offline scan | Yes on mobile local; API error if requesting expired snapshot | `SNAPSHOT_EXPIRED` for API request |

## 9. Open questions

- [ ] Có cần thêm result riêng cho manual override by admin/organizer không?
- [ ] `INVALID_QR_REJECTED` có áp dụng cho QR không decode được không, hay trường hợp đó luôn là `INVALID_QR_TOKEN` API error?
