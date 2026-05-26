/**
 * aster-api /api/v1/policies/evaluate baseline (DB-backed, prod path)
 *
 * Endpoint: POST /api/v1/policies/evaluate
 * Why a separate script from evaluate-source.js:
 *   - /evaluate is the production endpoint customer API keys hit
 *   - It uses the compiled-policy cache + Context-pool, so its
 *     scaling curve is very different (May 2026 sweep showed:
 *     ~4900 RPS sustained at c=64 vs evaluate-source which peaks
 *     ~1350 RPS at c=2 then falls off)
 *   - Thresholds are tighter because the path is hotter:
 *     P95 < 30ms (vs 200ms for evaluate-source)
 *     P99 < 100ms
 *
 * Prerequisites:
 *   - aster-api: ASTER_SECURITY_APIKEY_ENABLED=false (perf-env only)
 *   - DB pre-seeded with policy_versions row + policy_catalog row
 *     for `loadtest.zero.compute` returning constant 42. The CI
 *     workflow seeds this in the "Seed perf policy" step.
 *
 * Run locally:
 *   API_BASE=http://localhost:8080 k6 run evaluate.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const API_BASE = __ENV.API_BASE || 'http://localhost:8080';
const TENANT_ID = __ENV.TENANT_ID || 'perf-tenant';

const evalDuration = new Trend('eval_duration', true);
const evalErrors = new Rate('eval_errors');
const eval5xx = new Counter('eval_5xx');

export const options = {
  scenarios: {
    // Sustained-rate scenario at the level our May sweep proved
    // safe with zero errors. The "regression detector" works by
    // measuring p95/p99 at this load: a code change that adds
    // latency will fail the threshold before it ever ships.
    sustained: {
      executor: 'constant-arrival-rate',
      rate: 500,           // 500 RPS sustained
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 20,
      maxVUs: 80,
    },
  },
  thresholds: {
    'eval_duration': ['p(95)<30', 'p(99)<100'],
    'eval_errors':   ['rate<0.001'],
    'http_req_failed': ['rate<0.001'],
  },
};

const REQ = {
  policyModule: 'loadtest.zero',
  policyFunction: 'compute',
  context: [],
};

export default function () {
  const url = `${API_BASE}/api/v1/policies/evaluate`;
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': TENANT_ID,
      'X-User-Role': 'admin',
      'X-User-Id': 'perf-runner',
    },
    tags: { name: 'evaluate' },
    timeout: '5s',
  };

  const res = http.post(url, JSON.stringify(REQ), params);
  evalDuration.add(res.timings.duration);
  if (res.status >= 500) eval5xx.add(1);

  const ok = check(res, {
    'status 2xx': (r) => r.status >= 200 && r.status < 300,
    'response has result': (r) => r.body && r.body.includes('"result"'),
  });
  evalErrors.add(!ok);
}

export function handleSummary(data) {
  const m = data.metrics;
  const p95 = m.eval_duration?.values?.['p(95)'] ?? Infinity;
  const p99 = m.eval_duration?.values?.['p(99)'] ?? Infinity;
  const errRate = m.eval_errors?.values?.rate ?? 1;
  const total = m.http_reqs?.values?.count ?? 0;
  const fivexx = m.eval_5xx?.values?.count ?? 0;

  const text = `
========================================
aster-api /evaluate baseline (DB-backed)
========================================
  total requests:       ${total}
  5xx errors:           ${fivexx}
  error rate:           ${(errRate * 100).toFixed(3)}%

  Latency:
    avg:                ${m.eval_duration?.values?.avg?.toFixed(2) ?? '?'}ms
    P50:                ${m.eval_duration?.values?.med?.toFixed(2) ?? '?'}ms
    P95:                ${p95.toFixed(2)}ms
    P99:                ${p99.toFixed(2)}ms

  Acceptance:
    P95 < 30ms          ${p95 < 30 ? 'PASS' : 'FAIL'}
    P99 < 100ms         ${p99 < 100 ? 'PASS' : 'FAIL'}
    error rate < 0.1%   ${errRate < 0.001 ? 'PASS' : 'FAIL'}
========================================
`;

  return {
    stdout: text,
    'evaluate-summary.json': JSON.stringify(data, null, 2),
  };
}
