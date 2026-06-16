# Docker Runtime API Response Samples After Atomic By Token

Generated: 2026-06-14T15:39:27.6142452+07:00


## POST /internal/tickets/issue

```json
{
  "success": true,
  "data": {
    "id": "b2d066ee-e459-48f7-84c1-d92ac4f7fb5e",
    "orderId": "order-docker-8b87751d97e946cc961137d43ce46bf2",
    "orderItemId": "item-docker-f2f631531eca444eb02872a1483f32a1",
    "userId": "user-docker-1",
    "concertId": "concert-docker-ce5f068af41f4363a003f1896ed653ff",
    "ticketTypeId": "type-ga",
    "zoneId": "zone-ga",
    "ticketName": "General Admission",
    "status": "ISSUED",
    "qrToken": "ee81ca1c-f4ae-4efa-8624-c62a4a7eb846",
    "checkedInAt": null,
    "createdAt": "2026-06-14T08:39:28.209920288Z"
  },
  "error": null,
  "requestId": "6c00f93c-45ae-4f21-8e46-65ee831ee885",
  "timestamp": "2026-06-14T08:39:28.256835753Z"
}
```

## PUT /internal/tickets/by-token/{token}/check-in wrong concert

```json
{
  "success": true,
  "data": {
    "result": "WRONG_EVENT",
    "ticketId": "b2d066ee-e459-48f7-84c1-d92ac4f7fb5e",
    "concertId": "concert-docker-ce5f068af41f4363a003f1896ed653ff",
    "zoneId": "zone-ga",
    "zoneName": "General Admission",
    "holderName": "user-docker-1",
    "status": "ISSUED"
  },
  "error": null,
  "requestId": "2ed3cd13-0546-455f-8e09-3b1a1e4805c4",
  "timestamp": "2026-06-14T08:39:28.404204598Z"
}
```

## POST /api/checkin/scan accepted

```json
{
  "success": true,
  "data": {
    "result": "ACCEPTED",
    "ticketId": "b2d066ee-e459-48f7-84c1-d92ac4f7fb5e",
    "concertId": "concert-docker-ce5f068af41f4363a003f1896ed653ff",
    "gate": "A1",
    "scannedAt": "2026-06-14T08:39:28.706924483Z"
  },
  "error": null,
  "requestId": "1b2e5c38-f257-454b-af48-d4c9e9627998",
  "timestamp": "2026-06-14T08:39:28.909927044Z"
}
```

## POST /api/checkin/scan duplicate

```json
{
  "success": true,
  "data": {
    "result": "DUPLICATE_REJECTED",
    "ticketId": "b2d066ee-e459-48f7-84c1-d92ac4f7fb5e",
    "concertId": "concert-docker-ce5f068af41f4363a003f1896ed653ff",
    "gate": "A1",
    "scannedAt": "2026-06-14T08:39:28.975118355Z"
  },
  "error": null,
  "requestId": "a43f5448-7051-4db7-a3a9-89fa39e72257",
  "timestamp": "2026-06-14T08:39:28.997022577Z"
}
```

## GET /api/checkin/snapshot/{concertId}

```json
{
  "success": true,
  "data": {
    "concertId": "concert-docker-ce5f068af41f4363a003f1896ed653ff",
    "gate": null,
    "generatedAt": "2026-06-14T08:39:29.064934754Z",
    "expiresAt": "2026-06-14T14:39:29.064934754Z",
    "totalCount": 0,
    "tickets": []
  },
  "error": null,
  "requestId": "d8d16e3f-46b2-4c5a-8d32-56893b595f34",
  "timestamp": "2026-06-14T08:39:29.094412990Z"
}
```
