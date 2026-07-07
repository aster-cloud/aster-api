/**
 * aster-api /evaluate 容量压测（生产规格实测，非回归检测）
 *
 * 目的与既有 evaluate.js 的区别：
 *   - evaluate.js  = 回归检测：固定 500 RPS，看 p95/p99 是否守住阈值（PASS/FAIL）。
 *   - 本脚本        = 容量实测：阶梯递增 RPS 直到系统饱和，找出「单 pod 满负荷
 *                     吞吐上限」并定位瓶颈。用于 capacity planning，不是 CI 门禁。
 *
 * 必须在生产等价规格下跑（否则数字无意义）：
 *   容器限 CPU=0.5 核 / 堆 -Xmx384m（见 run-capacity.sh 和 CAPACITY.md）。
 *
 * 前置（与 evaluate.js 相同）：
 *   - DB 预置 policy_versions + policy_catalog 行，policyId=loadtest.zero.compute，返回常量 42
 *   - 关闭 API Key 校验：ASTER_SECURITY_APIKEY_ENABLED=false
 *   - 【关键】关闭限流：ASTER_RATELIMIT_ENABLED=false
 *     容量测试要打到 CPU/内存墙，不是限流墙（60/min）。限流墙由 capacity-ratelimit.js 单独验证。
 *
 * 运行：
 *   API_BASE=http://localhost:8080 k6 run capacity-evaluate.js
 *   # 可覆盖阶梯上限（默认到 1600 RPS）：
 *   MAX_RPS=2400 k6 run capacity-evaluate.js
 *
 * 输出：evaluate-capacity-summary.json + stdout 饱和点分析表。
 *
 * 如何读结果（瓶颈定位）：
 *   - 饱和点 = error_rate 首次突破 1% 或 p99 出现拐点（排队延迟爆炸）的那个台阶。
 *   - 若饱和时几乎无错误、只有 p99 陡增 → CPU 饱和（预期：/evaluate 缓存命中是纯 Truffle 计算）。
 *   - 若出现大量 5xx / OOM → 堆（384MB）+ 无界 CompiledPolicyCache 瓶颈。
 *   - 同时抓 `podman stats`：CPU 打满 ~100%（限额内）即 CPU-bound 确证。
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const API_BASE = __ENV.API_BASE || 'http://localhost:8080';
const TENANT_ID = __ENV.TENANT_ID || 'perf-tenant';
const MAX_RPS = parseInt(__ENV.MAX_RPS || '1600', 10);

// 分状态码计数 —— 这是容量测试区别于回归测试的核心：
// 不同状态码对应不同瓶颈，用于精确定位系统撞到了哪堵墙。
const evalDuration = new Trend('eval_duration', true);
const evalErrors = new Rate('eval_errors');
const c2xx = new Counter('status_2xx'); // 正常
const c429 = new Counter('status_429'); // 限流墙（本测应为 0，因限流已关）
const c503 = new Counter('status_503'); // 过载/信号量墙
const c5xx = new Counter('status_5xx'); // 5xx（OOM / 内部错误 → 堆瓶颈信号）
const cTimeout = new Counter('req_timeout'); // 客户端超时（排队延迟爆炸 → CPU 饱和信号）

// 阶梯加压：每个台阶稳态 45s 观察，逐级逼近饱和。
// 单 pod ½核预期在 ~800-1000 RPS 附近饱和；阶梯覆盖到 MAX_RPS 以便看到过载后行为。
// arrival-rate（开放模型）按目标 RPS 硬发压，不管系统能否跟上 → 才能观察到真实饱和拐点。
// （封闭 VU 模型会自我节流，永远看不到上限。）
function buildStages() {
  const targets = [200, 400, 600, 800, 1000, 1200, 1600, 2000, 2400, 3000].filter(
    (r) => r <= MAX_RPS,
  );
  const stages = [];
  for (const t of targets) {
    stages.push({ target: t, duration: '10s' }); // 爬升到该台阶
    stages.push({ target: t, duration: '45s' }); // 稳态观察
  }
  return stages;
}

export const options = {
  scenarios: {
    // 预热：ramp 到首台阶，填满编译缓存 + Context 池 + 预热 JIT。
    warmup: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 40,
      maxVUs: 200,
      stages: [{ target: 200, duration: '20s' }],
      tags: { phase: 'warmup' },
      exec: 'doEvaluate',
    },
    // 阶梯加压主场景。preAllocatedVUs 给足，避免 VU 不够导致的假饱和
    // （k6 warning "insufficient VUs" = 客户端瓶颈，不是被测系统瓶颈）。
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 800,
      stages: buildStages(),
      startTime: '25s', // 等 20s 预热 + 5s settle
      tags: { phase: 'ramp' },
      exec: 'doEvaluate',
    },
  },
  // 容量测试没有 PASS/FAIL 阈值（找上限，不是守阈值）；
  // 但保留一个宽松的软阈值只为在 k6 退出码里标记「测出了明显过载」。
  thresholds: {
    'eval_duration{phase:ramp}': ['p(99)<5000'], // 仅信息性
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  // 关键：按台阶 RPS 分桶输出子指标，让我们能画出「RPS→p99」饱和曲线。
  // k6 用 tags 实现；此处用 systemTags 保留 scenario/expected_response。
  systemTags: ['scenario', 'expected_response', 'status'],
};

const REQ = {
  policyModule: 'loadtest.zero',
  policyFunction: 'compute',
  context: [],
};

export function doEvaluate() {
  const url = `${API_BASE}/api/v1/policies/evaluate`;
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': TENANT_ID,
      'X-User-Role': 'admin',
      'X-User-Id': 'perf-runner',
    },
    tags: { name: 'evaluate' },
    timeout: '10s', // 比回归脚本(5s)长：过载时要观察排队延迟，不想过早判超时
  };

  const res = http.post(url, JSON.stringify(REQ), params);
  evalDuration.add(res.timings.duration);

  // 分类计数 —— 瓶颈定位的原始信号
  if (res.status === 0) cTimeout.add(1); // k6 记连接/超时错误为 status 0
  else if (res.status >= 200 && res.status < 300) c2xx.add(1);
  else if (res.status === 429) c429.add(1);
  else if (res.status === 503) c503.add(1);
  else if (res.status >= 500) c5xx.add(1);

  const ok = check(res, {
    'status 2xx': (r) => r.status >= 200 && r.status < 300,
    'response has result': (r) => r.body && r.body.includes('"result"'),
  });
  evalErrors.add(!ok);
}

export default doEvaluate;

export function handleSummary(data) {
  const m = data.metrics;
  const ramp = m['eval_duration{phase:ramp}'] ?? m.eval_duration ?? {};
  const v = ramp.values ?? {};
  const total = m.http_reqs?.values?.count ?? 0;
  const errRate = (m.eval_errors?.values?.rate ?? 0) * 100;

  const n2xx = m.status_2xx?.values?.count ?? 0;
  const n429 = m.status_429?.values?.count ?? 0;
  const n503 = m.status_503?.values?.count ?? 0;
  const n5xx = m.status_5xx?.values?.count ?? 0;
  const nTimeout = m.req_timeout?.values?.count ?? 0;

  // 瓶颈推断 —— 根据错误分布自动给出诊断
  let diagnosis;
  if (n503 > total * 0.01) {
    diagnosis = '过载拒绝为主 (503) → 线程池队列/信号量墙';
  } else if (n5xx > total * 0.01 || nTimeout > total * 0.01) {
    diagnosis = '5xx/超时为主 → 堆(384MB)瓶颈或 CPU 排队爆炸；结合 podman stats 内存判断';
  } else if (n429 > 0) {
    diagnosis = '出现 429 → 限流未关闭！请设 ASTER_RATELIMIT_ENABLED=false 重测';
  } else if ((v['p(99)'] ?? 0) > 1000) {
    diagnosis = '无明显错误但 p99 陡增 → CPU 饱和（纯计算路径的预期瓶颈）';
  } else {
    diagnosis = '未观察到饱和 → 提高 MAX_RPS 继续加压';
  }

  const text = `
========================================================
aster-api /evaluate 容量实测（生产规格）
  阶梯加压至 ${MAX_RPS} RPS · 限流应已关闭
========================================================
  总请求数:            ${total}
  达成吞吐 (整体均值):  ${(m.http_reqs?.values?.rate ?? 0).toFixed(0)} req/s

  状态码分布:
    2xx  (正常):        ${n2xx}
    429  (限流墙):      ${n429}   ${n429 > 0 ? '⚠ 限流没关' : ''}
    503  (过载/信号量): ${n503}
    5xx  (错误/OOM):    ${n5xx}
    超时 (排队爆炸):    ${nTimeout}
    错误率:             ${errRate.toFixed(3)}%

  延迟 (ramp 阶段):
    P50:                ${v.med?.toFixed(2) ?? '?'}ms
    P95:                ${v['p(95)']?.toFixed(2) ?? '?'}ms
    P99:                ${v['p(99)']?.toFixed(2) ?? '?'}ms
    max:                ${v.max?.toFixed(2) ?? '?'}ms

  瓶颈诊断:
    ${diagnosis}

  ↓ 找单 pod 饱和 RPS：看 JSON 里按台阶分桶的 p99 曲线，
    或用 --out csv 导出后画 RPS→p99 拐点。
    单 pod 上限 × 有效 pod 数（HPA 2–6）= 全系统吞吐（注意共享 DB 非线性）。
========================================================
`;

  return {
    stdout: text,
    'evaluate-capacity-summary.json': JSON.stringify(data, null, 2),
  };
}
