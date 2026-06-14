# Docker Runtime API Response Samples

Generated: 2026-06-14T14:45:26.1377545+07:00


## POST /internal/tickets/issue

```json
{
  "success": true,
  "data": {
    "id": "5ba20d6e-9964-4e20-8450-e022a02011ee",
    "orderId": "order-docker-5333d5644c464a0f9ea43789e47f0c1c",
    "orderItemId": "item-docker-63352b6168934faab6330019b0f006ee",
    "userId": "user-docker-1",
    "concertId": "concert-docker-70f90772e4df483bb6d18bf45edbb500",
    "ticketTypeId": "type-ga",
    "zoneId": "zone-ga",
    "ticketName": "General Admission",
    "status": "ISSUED",
    "qrToken": "cfe730f2-e3be-4fb4-b283-14bb470187be",
    "checkedInAt": null,
    "createdAt": "2026-06-14T07:45:26.527279726Z"
  },
  "error": null,
  "requestId": "9f0046ee-ee70-4888-8e91-e7eebbaac957",
  "timestamp": "2026-06-14T07:45:26.559506505Z"
}
```

## POST /internal/tickets/issue duplicate

```json
{
  "success": true,
  "data": {
    "id": "5ba20d6e-9964-4e20-8450-e022a02011ee",
    "orderId": "order-docker-5333d5644c464a0f9ea43789e47f0c1c",
    "orderItemId": "item-docker-63352b6168934faab6330019b0f006ee",
    "userId": "user-docker-1",
    "concertId": "concert-docker-70f90772e4df483bb6d18bf45edbb500",
    "ticketTypeId": "type-ga",
    "zoneId": "zone-ga",
    "ticketName": "General Admission",
    "status": "ISSUED",
    "qrToken": "cfe730f2-e3be-4fb4-b283-14bb470187be",
    "checkedInAt": null,
    "createdAt": "2026-06-14T07:45:26.52728Z"
  },
  "error": null,
  "requestId": "b5acec0e-7c9c-49fe-bfa1-41be9801109f",
  "timestamp": "2026-06-14T07:45:26.677540544Z"
}
```

## GET /internal/tickets/by-token/{token}

```json
{
  "success": true,
  "data": {
    "id": "5ba20d6e-9964-4e20-8450-e022a02011ee",
    "orderId": "order-docker-5333d5644c464a0f9ea43789e47f0c1c",
    "orderItemId": "item-docker-63352b6168934faab6330019b0f006ee",
    "userId": "user-docker-1",
    "concertId": "concert-docker-70f90772e4df483bb6d18bf45edbb500",
    "ticketTypeId": "type-ga",
    "zoneId": "zone-ga",
    "ticketName": "General Admission",
    "status": "ISSUED",
    "qrToken": "cfe730f2-e3be-4fb4-b283-14bb470187be",
    "checkedInAt": null,
    "createdAt": "2026-06-14T07:45:26.52728Z"
  },
  "error": null,
  "requestId": "ee4d475f-c61b-4b48-a66b-78228502deaa",
  "timestamp": "2026-06-14T07:45:26.749986791Z"
}
```

## POST /api/checkin/scan accepted

```json
{
  "success": true,
  "data": {
    "result": "ACCEPTED",
    "ticketId": "5ba20d6e-9964-4e20-8450-e022a02011ee",
    "concertId": "concert-docker-70f90772e4df483bb6d18bf45edbb500",
    "gate": "A1",
    "scannedAt": "2026-06-14T07:45:26.973724526Z"
  },
  "error": null,
  "requestId": "2bbb3e40-08e3-48fa-9498-7f32077458b7",
  "timestamp": "2026-06-14T07:45:27.144921615Z"
}
```

## POST /api/checkin/scan duplicate

```json
{
  "success": true,
  "data": {
    "result": "DUPLICATE_REJECTED",
    "ticketId": "5ba20d6e-9964-4e20-8450-e022a02011ee",
    "concertId": "concert-docker-70f90772e4df483bb6d18bf45edbb500",
    "gate": "A1",
    "scannedAt": "2026-06-14T07:45:27.217386848Z"
  },
  "error": null,
  "requestId": "bd55fdeb-b009-4253-996e-e16309e44c67",
  "timestamp": "2026-06-14T07:45:27.235655034Z"
}
```

## GET /api/checkin/snapshot/{concertId}

```json
{
  "success": true,
  "data": {
    "concertId": "concert-docker-70f90772e4df483bb6d18bf45edbb500",
    "gate": null,
    "generatedAt": "2026-06-14T07:45:27.297501731Z",
    "expiresAt": "2026-06-14T13:45:27.297501731Z",
    "totalCount": 0,
    "tickets": []
  },
  "error": null,
  "requestId": "0ceed42c-c61f-49c2-89dd-884d8a514e87",
  "timestamp": "2026-06-14T07:45:27.316580689Z"
}
```
