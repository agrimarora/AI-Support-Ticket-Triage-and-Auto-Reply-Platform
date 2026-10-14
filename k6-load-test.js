import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 20 },  // Ramp-up to 20 virtual users
    { duration: '30s', target: 50 },  // Maintain 50 virtual users
    { duration: '10s', target: 0 },   // Ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'], // 95% of requests must complete below 500ms
    http_req_failed: ['rate<0.01'],   // Error rate must be under 1%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const API_KEY = __ENV.API_KEY || 'tk_alpha_loadtest';

export default function () {
  const payload = JSON.stringify({
    customerId: `cust-${__VU}-${__ITER}`,
    title: `Payment failure invoice #${__ITER}`,
    description: 'Received billing error when card was processed for renewal.',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
      'Idempotency-Key': `k6-${__VU}-${__ITER}`,
    },
  };

  const res = http.post(`${BASE_URL}/v1/tickets`, payload, params);

  check(res, {
    'status is 202': (r) => r.status === 202,
    'has ticket id': (r) => JSON.parse(r.body).id !== undefined,
  });

  sleep(0.1);
}
