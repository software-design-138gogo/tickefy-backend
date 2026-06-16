# Docker Tuned Runtime API Samples

Generated: 2026-06-14T15:46:08.1539776+07:00

Issue:
{
  "success": true,
  "data": {
    "id": "e793dbaa-9f1e-4ed6-a230-cb78ca671bc1",
    "orderId": "order-tuned-5c202b16e292451a94fb265dc4632f6a",
    "orderItemId": "item-tuned-af7cf84e89ab4acfa64165cca019cf80",
    "userId": "user-tuned",
    "concertId": "concert-tuned-a3467253f12441af948fed7b29a15159",
    "ticketTypeId": "type-ga",
    "zoneId": "zone-ga",
    "ticketName": "General Admission",
    "status": "ISSUED",
    "qrToken": "a6b55352-b106-4eec-a124-c4746f8c5978",
    "checkedInAt": null,
    "createdAt": "2026-06-14T08:46:07.510012614Z"
  },
  "error": null,
  "requestId": "57d4dba3-86b7-4aeb-95b9-ca40ba687fa9",
  "timestamp": "2026-06-14T08:46:07.581464639Z"
}

Wrong concert:
{
  "success": true,
  "data": {
    "result": "WRONG_EVENT",
    "ticketId": "e793dbaa-9f1e-4ed6-a230-cb78ca671bc1",
    "concertId": "concert-tuned-a3467253f12441af948fed7b29a15159",
    "zoneId": "zone-ga",
    "zoneName": "General Admission",
    "holderName": "user-tuned",
    "status": "ISSUED"
  },
  "error": null,
  "requestId": "7ff80bf3-0444-4c63-9f29-8827067cb5b2",
  "timestamp": "2026-06-14T08:46:07.669912803Z"
}

Accepted scan:
{
  "success": true,
  "data": {
    "result": "ACCEPTED",
    "ticketId": "e793dbaa-9f1e-4ed6-a230-cb78ca671bc1",
    "concertId": "concert-tuned-a3467253f12441af948fed7b29a15159",
    "gate": "A",
    "scannedAt": "2026-06-14T08:46:07.939902713Z"
  },
  "error": null,
  "requestId": "6309483e-e5ed-4e0f-bb1c-86b5794d5754",
  "timestamp": "2026-06-14T08:46:08.115159902Z"
}

Duplicate scan:
{
  "success": true,
  "data": {
    "result": "DUPLICATE_REJECTED",
    "ticketId": "e793dbaa-9f1e-4ed6-a230-cb78ca671bc1",
    "concertId": "concert-tuned-a3467253f12441af948fed7b29a15159",
    "gate": "A",
    "scannedAt": "2026-06-14T08:46:08.131488289Z"
  },
  "error": null,
  "requestId": "53a49d0f-dd29-45d2-8c16-211d37e4a409",
  "timestamp": "2026-06-14T08:46:08.149602571Z"
}

