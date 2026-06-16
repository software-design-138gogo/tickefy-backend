import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';

const vus = Number(__ENV.VUS || 1000);
const seedTickets = Number(__ENV.SEED_TICKETS || vus);
const jitterSeconds = Number(__ENV.JITTER_SECONDS || 20);

const eticketBaseUrl = __ENV.ETICKET_BASE_URL || 'http://tickefy-e-ticket-service-evidence:8080';
const checkinBaseUrl = __ENV.CHECKIN_BASE_URL || 'http://tickefy-checkin-service-evidence:8080';
const adminToken = __ENV.ADMIN_TOKEN;
const staffToken = __ENV.STAFF_TOKEN;

export const options = {
  scenarios: {
    thousand_user_paced_checkin: {
      executor: 'per-vu-iterations',
      vus,
      iterations: 2,
      maxDuration: __ENV.MAX_DURATION || '3m',
    },
  },
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.10'],
    http_req_duration: ['p(95)<5000'],
  },
};

function jsonHeaders(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    timeout: '30s',
  };
}

function parseJson(response, label) {
  try {
    return response.json();
  } catch (error) {
    fail(`${label} returned non-json status=${response.status}`);
  }
}

export function setup() {
  if (!adminToken || !staffToken) {
    fail('ADMIN_TOKEN and STAFF_TOKEN are required');
  }

  const concertId = `concert-k6-paced-${Date.now()}`;
  const tickets = [];

  for (let i = 0; i < seedTickets; i += 1) {
    const body = JSON.stringify({
      orderId: `order-k6-paced-${concertId}-${i}`,
      orderItemId: `item-k6-paced-${concertId}-${i}`,
      userId: `user-k6-paced-${i}`,
      concertId,
      ticketTypeId: 'type-ga',
      zoneId: 'zone-ga',
      ticketName: 'General Admission',
    });

    const response = http.post(
      `${eticketBaseUrl}/internal/tickets/issue`,
      body,
      jsonHeaders(adminToken),
    );

    const ok = check(response, {
      'seed issue status is 201 or 200': (r) => r.status === 201 || r.status === 200,
    });
    if (!ok) {
      fail(`seed issue failed at index=${i} status=${response.status} body=${response.body}`);
    }

    const payload = parseJson(response, 'seed issue');
    if (!payload.success || !payload.data?.qrToken) {
      fail(`seed issue envelope invalid at index=${i} body=${response.body}`);
    }

    tickets.push(payload.data.qrToken);
  }

  return { concertId, tickets };
}

export default function (data) {
  sleep(Math.random() * jitterSeconds);

  const vuIndex = exec.vu.idInTest - 1;
  const qrToken = data.tickets[vuIndex % data.tickets.length];
  const body = JSON.stringify({
    qrToken,
    concertId: data.concertId,
    deviceId: `k6-device-${exec.vu.idInTest}`,
    gate: 'LOAD',
  });

  const response = http.post(
    `${checkinBaseUrl}/api/checkin/scan`,
    body,
    jsonHeaders(staffToken),
  );

  check(response, {
    'scan http 200': (r) => r.status === 200,
    'scan business result accepted or duplicate': (r) => {
      const payload = parseJson(r, 'scan');
      return payload.success === true
        && (payload.data?.result === 'ACCEPTED' || payload.data?.result === 'DUPLICATE_REJECTED');
    },
    'scan response does not leak raw qrToken field': (r) => !r.body.includes('"qrToken"'),
  });
}
