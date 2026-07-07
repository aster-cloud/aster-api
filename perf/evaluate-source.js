/**
 * aster-api evaluate-source 性能基线
 *
 * Endpoint: POST /api/v1/policies/evaluate-source
 * 阈值（P0-3）：P95 < 200ms, 5xx error rate < 0.1%
 *
 * 运行：
 *   API_BASE=http://localhost:8080 k6 run evaluate-source.js
 *
 * 生产 perf-env：
 *   API_BASE=http://aster-api.perf-env.svc.cluster.local k6 run evaluate-source.js
 *
 * 前置：
 *   - aster-api 需禁用 HMAC 签名验证（ASTER_SECURITY_SIGNATURE_ENABLED=false）
 *     生产环境 perf-env namespace 单独配置
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
    sustained: {
      executor: 'constant-arrival-rate',
      rate: 50,            // 50 RPS
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 10,
      maxVUs: 30,
    },
  },
  thresholds: {
    'eval_duration': ['p(95)<200', 'p(99)<500'],
    'eval_errors':   ['rate<0.001'],
    'http_req_failed': ['rate<0.001'],
  },
  // k6 default trend stats omit p(99); handleSummary's "P99 < 500ms FAIL"
  // display would always show FAIL due to Infinity fallback even when the
  // actual threshold passed.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// 5 条 tier1 等价规则的最简代表（覆盖多 lexicon + 常见控制流）。
// 用当前有效的块式 CNL 语法（Module. / Rule … produce: / Return / at least /
// is equal to）。旧版的终端简写形式（`given amount: amount > 100.`、内联
// `if … then … else`）在 Phase 1-2 解析器迁移后已不再解析，会导致每个请求
// 返回 CNL 语法错误——本脚本因此长期在测「错误响应延迟」而非真实编译。
// 以下 5 条均已对当前编译器实测通过。
const SAMPLES = [
  {
    source:
      'Module perf.simple.\n\nRule evaluate given amount, produce:\n' +
      '  If amount at least 100\n    Return true.\n  Return false.',
    context: { amount: 150 },
    functionName: 'evaluate',
  },
  {
    source:
      'Module perf.compare.\n\nRule evaluate given score, produce:\n' +
      '  If score at least 60\n    Return true.\n  Return false.',
    context: { score: 75 },
    functionName: 'evaluate',
  },
  {
    source:
      'Module perf.ifthen.\n\nRule evaluate given age, produce:\n' +
      '  If age at least 18\n    Return "adult".\n  Return "minor".',
    context: { age: 25 },
    functionName: 'evaluate',
  },
  {
    source: 'Module perf.arith.\n\nRule evaluate given a, b, produce:\n  Return a * 2 + b.',
    context: { a: 10, b: 5 },
    functionName: 'evaluate',
  },
  {
    source:
      'Module perf.string.\n\nRule evaluate given name, produce:\n' +
      '  If name is equal to "alice"\n    Return true.\n  Return false.',
    context: { name: 'alice' },
    functionName: 'evaluate',
  },
];

export default function () {
  const sample = SAMPLES[Math.floor(Math.random() * SAMPLES.length)];
  const url = `${API_BASE}/api/v1/policies/evaluate-source`;
  const body = JSON.stringify({
    source: sample.source,
    context: sample.context,
    locale: 'en-US',
    functionName: sample.functionName,
  });
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': TENANT_ID,
    },
    tags: { name: 'evaluate_source' },
    timeout: '5s',
  };

  const res = http.post(url, body, params);
  evalDuration.add(res.timings.duration);
  if (res.status >= 500) eval5xx.add(1);

  const ok = check(res, {
    'status 2xx': (r) => r.status >= 200 && r.status < 300,
    'response has body': (r) => r.body && r.body.length > 0,
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
aster-api evaluate-source baseline
========================================
  total requests:       ${total}
  5xx errors:           ${fivexx}
  error rate:           ${(errRate * 100).toFixed(3)}%

  Latency:
    avg:                ${m.eval_duration?.values?.avg?.toFixed(2) ?? '?'}ms
    P50:                ${m.eval_duration?.values?.med?.toFixed(2) ?? '?'}ms
    P95:                ${p95.toFixed(2)}ms
    P99:                ${p99.toFixed(2)}ms

  Acceptance (P0-3):
    P95 < 200ms         ${p95 < 200 ? 'PASS' : 'FAIL'}
    P99 < 500ms         ${p99 < 500 ? 'PASS' : 'FAIL'}
    error rate < 0.1%   ${errRate < 0.001 ? 'PASS' : 'FAIL'}
========================================
`;

  return {
    stdout: text,
    'policy-eval-summary.json': JSON.stringify(data, null, 2),
  };
}
