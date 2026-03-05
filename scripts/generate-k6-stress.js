import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

function envNumber(name, fallback, { min, max, integer = false } = {}) {
  const raw = __ENV[name];
  const value = raw === undefined || raw === '' ? fallback : Number(raw);

  if (!Number.isFinite(value)) {
    throw new Error(`${name} must be a finite number, got '${raw}'`);
  }
  if (integer && !Number.isInteger(value)) {
    throw new Error(`${name} must be an integer, got ${value}`);
  }
  if (min !== undefined && value < min) {
    throw new Error(`${name} must be >= ${min}, got ${value}`);
  }
  if (max !== undefined && value > max) {
    throw new Error(`${name} must be <= ${max}, got ${value}`);
  }

  return value;
}

const baseUrl = __ENV.TRANSACTION_API_BASE || 'http://localhost:8080';
const testType = (__ENV.TEST_TYPE || 'stress').toLowerCase();
const profileName = __ENV.TEST_PROFILE || 'capacity-baseline';
const requestTimeout = __ENV.REQUEST_TIMEOUT || '4s';
const webhookRatio = envNumber('WEBHOOK_RATIO', 35, { min: 0, max: 100 });

const normalWeight = envNumber('NORMAL_WEIGHT', 80, { min: 0 });
const fraudWeight = envNumber('FRAUD_WEIGHT', 12, { min: 0 });
const velocityWeight = envNumber('VELOCITY_WEIGHT', 5, { min: 0 });
const invalidWeight = envNumber('INVALID_WEIGHT', 3, { min: 0 });
const error5xxWeight = envNumber('ERROR5XX_WEIGHT', 0, { min: 0 });

const weightTotal =
  normalWeight + fraudWeight + velocityWeight + invalidWeight + error5xxWeight;
if (Math.abs(weightTotal - 100) > 0.000001) {
  throw new Error(`Profile weights must add up to 100, got ${weightTotal}`);
}

const users = envNumber('USERS', 600, { min: 1, integer: true });
const targetRps = envNumber('STRESS_RPS', 250, { min: 1, integer: true });
const steadyDuration = __ENV.STRESS_DURATION || '4m';
const preAllocatedVUs = envNumber('PREALLOCATED_VUS', 350, {
  min: 1,
  integer: true,
});
const maxVUs = envNumber('MAX_VUS', 2500, { min: 1, integer: true });
const supportedTestTypes = ['stress', 'spike', 'soak', 'smoke'];

if (!supportedTestTypes.includes(testType)) {
  throw new Error(
    `Unsupported TEST_TYPE '${testType}'. Valid values: ${supportedTestTypes.join(', ')}`,
  );
}

if (preAllocatedVUs > maxVUs) {
  throw new Error('PREALLOCATED_VUS cannot be greater than MAX_VUS');
}

const safeMerchants = ['MRC-101', 'MRC-202', 'MRC-303', 'MRC-505'];
const riskyMerchants = ['MRC-999', 'MRC-666', 'MRC-404'];
const countries = ['US', 'BR', 'MX', 'AR', 'CL', 'PE'];
const paymentMethods = ['CARD', 'TRANSFER', 'WALLET'];
const hotUsers = ['k6-hot-1', 'k6-hot-2', 'k6-hot-3', 'k6-hot-4'];

const status2xx = new Counter('status_2xx');
const status4xx = new Counter('status_4xx');
const status5xx = new Counter('status_5xx');
const statusOther = new Counter('status_other');

function buildStages(kind, rps, duration) {
  const low = Math.max(20, Math.floor(rps * 0.3));
  const medium = Math.max(60, Math.floor(rps * 0.6));

  switch (kind) {
    case 'smoke':
      return [
        { target: low, duration: '20s' },
        { target: low, duration },
        { target: 0, duration: '10s' },
      ];
    case 'spike':
      return [
        { target: medium, duration: '30s' },
        { target: Math.max(100, Math.floor(rps * 1.6)), duration: '30s' },
        { target: medium, duration: '30s' },
        { target: Math.max(40, Math.floor(rps * 0.4)), duration },
        { target: 0, duration: '20s' },
      ];
    case 'soak':
      return [
        { target: Math.max(80, Math.floor(rps * 0.7)), duration: '1m' },
        { target: Math.max(80, Math.floor(rps * 0.7)), duration },
        { target: 0, duration: '30s' },
      ];
    case 'stress':
    default:
      return [
        { target: Math.max(100, Math.floor(rps * 0.6)), duration: '45s' },
        { target: rps, duration },
        { target: Math.max(80, Math.floor(rps * 0.5)), duration: '45s' },
        { target: 0, duration: '20s' },
      ];
  }
}

const scenarioName = `${testType}_load`;

export const options = {
  tags: {
    test_type: testType,
    traffic_profile: profileName,
  },
  scenarios: {
    [scenarioName]: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(50, Math.floor(targetRps * 0.2)),
      timeUnit: '1s',
      preAllocatedVUs,
      maxVUs,
      stages: buildStages(testType, targetRps, steadyDuration),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.15'],
    http_req_duration: ['p(95)<3500', 'p(99)<5000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  console.log(
    `[k6] testType=${testType} profile=${profileName} targetRps=${targetRps} duration=${steadyDuration} baseUrl=${baseUrl}`,
  );
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function randomAmount(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function endpoint() {
  return Math.random() * 100 < webhookRatio
    ? '/api/v1/webhooks/transactions'
    : '/api/v1/transactions';
}

function classifyStatus(code) {
  if (code >= 200 && code < 300) status2xx.add(1);
  else if (code >= 400 && code < 500) status4xx.add(1);
  else if (code >= 500 && code < 600) status5xx.add(1);
  else statusOther.add(1);
}

function send(payload) {
  const res = http.post(`${baseUrl}${endpoint()}`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
    timeout: requestTimeout,
  });

  classifyStatus(res.status);
  check(res, {
    'status is expected class': (r) => r.status >= 200 && r.status < 600,
  });
}

function normalPayload() {
  return {
    userId: `k6-user-${Math.floor(Math.random() * users) + 1}`,
    amount: randomAmount(20, 1800),
    currency: 'USD',
    merchantId: pick(safeMerchants),
    country: pick(countries),
    paymentMethod: pick(paymentMethods),
  };
}

function fraudPayload() {
  const profile = Math.floor(Math.random() * 100);
  if (profile < 30) {
    return {
      userId: `k6-fraud-${Math.floor(Math.random() * users) + 1}`,
      amount: randomAmount(12000, 26000),
      currency: 'USD',
      merchantId: pick(safeMerchants),
      country: pick(countries),
      paymentMethod: pick(paymentMethods),
    };
  }
  if (profile < 60) {
    return {
      userId: `k6-fraud-${Math.floor(Math.random() * users) + 1}`,
      amount: randomAmount(300, 3500),
      currency: 'USD',
      merchantId: pick(riskyMerchants),
      country: pick(countries),
      paymentMethod: pick(paymentMethods),
    };
  }
  return {
    userId: `k6-fraud-${Math.floor(Math.random() * users) + 1}`,
    amount: randomAmount(14000, 32000),
    currency: 'USD',
    merchantId: pick(riskyMerchants),
    country: 'BR',
    paymentMethod: 'CARD',
  };
}

function velocityPayload() {
  return {
    userId: pick(hotUsers),
    amount: randomAmount(150, 2500),
    currency: 'USD',
    merchantId: pick(safeMerchants),
    country: pick(countries),
    paymentMethod: 'CARD',
  };
}

function invalidPayload() {
  return {
    userId: '',
    amount: -10,
    currency: 'usd',
    merchantId: '',
    country: 'USA',
    paymentMethod: 'CARD',
  };
}

function serverErrorPayload() {
  return {
    userId: `k6-5xx-${Math.floor(Math.random() * users) + 1}`,
    amount: Number.MAX_SAFE_INTEGER,
    currency: 'USD',
    merchantId: pick(safeMerchants),
    country: pick(countries),
    paymentMethod: 'CARD',
  };
}

export default function () {
  const roll = Math.random() * 100;
  if (roll < normalWeight) {
    send(normalPayload());
    return;
  }
  if (roll < normalWeight + fraudWeight) {
    send(fraudPayload());
    return;
  }
  if (roll < normalWeight + fraudWeight + velocityWeight) {
    send(velocityPayload());
    return;
  }
  if (roll < normalWeight + fraudWeight + velocityWeight + invalidWeight) {
    send(invalidPayload());
    return;
  }
  send(serverErrorPayload());
}
