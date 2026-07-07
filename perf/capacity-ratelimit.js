/**
 * aster-api 限流墙实测（验证单租户 60 请求/60 秒/pod 上限）
 *
 * 这是三个容量脚本里唯一「限流开启」的：另两个关限流去压 CPU/信号量，
 * 本脚本反过来，专门确认限流闸门本身的形状。
 *
 * 被测配置（application.properties:361-362, RateLimitFilter.java）：
 *   aster.ratelimit.rest.max-requests = 60
 *   aster.ratelimit.rest.window-seconds = 60
 *   key = 每租户（TenantContext 已初始化）或 每 IP（未初始化时）
 *   限流是「进程内非共享」（每 pod 独立 ConcurrentHashMap）。
 *
 * 预期实测结论：
 *   - 单 pod：单租户稳态可通过 ≈ 60 请求/分钟，之后收 429。
 *   - N pod + LB 轮询：单租户 ≈ 60×N/分钟（因为非共享，每 pod 各算各的）。
 *   - 即换算：单租户一小时 ≈ 60 × pod数 × 60 次。6 pod → ≈ 21,600 次/小时。
 *
 * 前置：
 *   - 【关键】限流必须开启：ASTER_RATELIMIT_ENABLED=true（生产默认即 true）
 *   - 本测直连单 pod（不经 LB），故量到的是「单 pod 单租户」= 60/min 基准值。
 *     要验证 N-pod 聚合，把 API_BASE 指向 Service/Ingress 并起 N 副本。
 *
 * 用 /evaluate-source 做探针（无需 DB 预置）。请求打同一个 X-Tenant-Id
 * 以命中 per-tenant 桶。注意：因 RateLimitFilter 跑在 TenantFilter 之前，
 * 多数请求其实落到 per-IP 分支（见 RateLimitFilter.java:91-103 注释）——
 * 单 IP 单租户压测下两者行为一致，都收敛到 60/window。
 *
 * 运行：
 *   API_BASE=http://localhost:8080 k6 run capacity-ratelimit.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const API_BASE = __ENV.API_BASE || 'http://localhost:8080';
const TENANT_ID = __ENV.TENANT_ID || 'perf-ratelimit-tenant';

const c2xx = new Counter('status_2xx');
const c429 = new Counter('status_429'); // 限流命中 —— 本测核心信号
const cOther = new Counter('status_other');
const passRate = new Rate('pass_rate');
const firstBlock = new Trend('first_429_second'); // 首次 429 出现在第几秒（应 ≈ 达到 60 次时）

/**
 * 稳态发压 2 个 window（120s）明显超过限额，观察允许/拒绝比例是否收敛到 60/min。
 * 用固定 2 RPS（=120/min，正好限额的 2 倍）：预期约一半被 429。
 * 再叠加一个突发场景：单秒打 100 次，验证瞬时也是硬砍到 60/window。
 */
export const options = {
  scenarios: {
    // 场景 1：稳态 2 RPS × 130s（跨 ~2 个 window），看 2xx 是否收敛到 ≈60/window。
    steady: {
      executor: 'constant-arrival-rate',
      rate: 2,
      timeUnit: '1s',
      duration: '130s',
      preAllocatedVUs: 5,
      maxVUs: 20,
      tags: { phase: 'steady' },
    },
    // 场景 2：突发——在稳态跑完后，1 秒内灌 100 次，验证瞬时也被砍到限额内。
    burst: {
      executor: 'per-vu-iterations',
      vus: 100,
      iterations: 1,
      startTime: '135s',
      maxDuration: '10s',
      tags: { phase: 'burst' },
    },
  },
  thresholds: {
    // 无 PASS/FAIL 门禁；只要观察分布。保留占位避免 k6 抱怨无阈值。
    pass_rate: ['rate>=0'],
  },
};

function hit() {
  const url = `${API_BASE}/api/v1/policies/evaluate-source`;
  const body = JSON.stringify({
    // 当前有效 CNL 语法（produce:/Return）；终端 `amount > N.` 简写已不解析。
    source:
      'Module perf.rl.\n\nRule evaluate given amount, produce:\n' +
      '  If amount at least 100\n    Return true.\n  Return false.',
    context: { amount: 150 },
    locale: 'en-US',
    functionName: 'evaluate',
  });
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': TENANT_ID,
    },
    tags: { name: 'ratelimit_probe' },
    timeout: '5s',
  };

  const res = http.post(url, body, params);
  const allowed = res.status >= 200 && res.status < 300;
  if (allowed) c2xx.add(1);
  else if (res.status === 429) {
    c429.add(1);
    firstBlock.add((Date.now() - startMs) / 1000);
  } else cOther.add(1);
  passRate.add(allowed);

  check(res, {
    'allowed or ratelimited (no 5xx)': (r) =>
      (r.status >= 200 && r.status < 300) || r.status === 429,
  });
}

// k6 init 阶段不允许 Date.now()，放到 setup。
let startMs = 0;
export function setup() {
  return { start: Date.now() };
}
export default function (data) {
  if (!startMs) startMs = data.start;
  hit();
}

export function handleSummary(data) {
  const m = data.metrics;
  const n2xx = m.status_2xx?.values?.count ?? 0;
  const n429 = m.status_429?.values?.count ?? 0;
  const nOther = m.status_other?.values?.count ?? 0;
  const total = n2xx + n429 + nOther;

  // 稳态 130s ≈ 2.17 个 window；理论允许 ≈ 60 × 2.17 ≈ 130 次（单 pod）。
  // 若实测 2xx 明显 ≈ 每分钟 60，即证实限流墙形状。
  const perMin = total > 0 ? ((n2xx / 140) * 60).toFixed(1) : '0';

  let diagnosis;
  if (nOther > 0) {
    diagnosis = `出现 ${nOther} 个非 2xx/429 响应 → 检查是否 HMAC/其他过滤器干扰`;
  } else if (n429 === 0) {
    diagnosis =
      '零 429 → 限流未生效！确认 ASTER_RATELIMIT_ENABLED=true 且发压速率 > 60/min';
  } else {
    diagnosis =
      '观察到 429 限流生效。2xx 通过量 ÷ 时长 应收敛到 ≈60/min（单 pod 单租户基准）';
  }

  const text = `
========================================================
aster-api 限流墙实测（单 pod 单租户）
  限流应开启 · 稳态 2 RPS×130s + 突发 100/1s
========================================================
  总请求:              ${total}
  2xx  (放行):         ${n2xx}
  429  (限流拒绝):     ${n429}
  其他:                ${nOther}

  换算单租户放行速率:  ≈ ${perMin} 次/分钟   （目标基准 60/min/pod）
  → 单租户一小时上限:  ≈ ${(parseFloat(perMin) * 60).toFixed(0)} 次/pod
     N 个 pod（限流非共享）≈ 上值 × N

  诊断:
    ${diagnosis}
========================================================
`;

  return {
    stdout: text,
    'ratelimit-capacity-summary.json': JSON.stringify(data, null, 2),
  };
}
