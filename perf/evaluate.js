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
    // 30s warmup at 50 RPS — primes the JIT, fills the compiled-policy
    // cache, opens DB connection pool. Production runs already-warm;
    // CI starts cold so without warmup the first ~5s of the sustained
    // scenario times out 5-6% of requests. Warmup tagged `phase:warmup`
    // so it doesn't pollute the sustained metrics.
    warmup: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      maxVUs: 30,
      tags: { phase: 'warmup' },
      exec: 'warmup',
    },
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
      startTime: '35s',    // wait for warmup + 5s settle
      tags: { phase: 'sustained' },
    },
  },
  thresholds: {
    // p99/p95/error-rate scoped to sustained phase only (warmup
    // intentionally cold so we don't gate on its latency).
    'eval_duration{phase:sustained}': ['p(95)<30', 'p(99)<100'],
    'eval_errors{phase:sustained}':   ['rate<0.001'],
    'http_req_failed{phase:sustained}': ['rate<0.001'],
  },
  // k6 default trend stats omit p(99); handleSummary needs it for the
  // text report. Default = ["avg","min","med","max","p(90)","p(95)"].
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const REQ = {
  policyModule: 'loadtest.zero',
  policyFunction: 'compute',
  context: [],
};

function doEvaluate() {
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

// Default export = sustained scenario worker
export default doEvaluate;

// Warmup scenario worker (same logic, different scenario tag)
export function warmup() {
  doEvaluate();
}

export function handleSummary(data) {
  const m = data.metrics;
  // Acceptance metrics are sustained-phase only (matches thresholds).
  // Warmup phase intentionally cold; reporting its latency would be misleading.
  const sustained = m['eval_duration{phase:sustained}'] ?? m.eval_duration ?? {};
  const sustainedErrors = m['eval_errors{phase:sustained}'] ?? m.eval_errors ?? {};
  const p95 = sustained.values?.['p(95)'] ?? Infinity;
  const p99 = sustained.values?.['p(99)'] ?? Infinity;
  const errRate = sustainedErrors.values?.rate ?? 1;
  const total = m.http_reqs?.values?.count ?? 0;
  const fivexx = m.eval_5xx?.values?.count ?? 0;

  const text = `
========================================
aster-api /evaluate baseline (DB-backed)
  (warmup: 30s @ 50 RPS, sustained: 3m @ 500 RPS — acceptance ↓ scoped to sustained)
========================================
  total requests:       ${total}
  5xx errors (all):     ${fivexx}
  error rate (sust):    ${(errRate * 100).toFixed(3)}%

  Latency (sustained):
    avg:                ${sustained.values?.avg?.toFixed(2) ?? '?'}ms
    P50:                ${sustained.values?.med?.toFixed(2) ?? '?'}ms
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
