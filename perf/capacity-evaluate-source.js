/**
 * aster-api /evaluate-source 容量压测（生产规格，验证信号量墙 cap=2）
 *
 * 与既有 evaluate-source.js 的区别：
 *   - evaluate-source.js = 回归检测：固定 50 RPS，5 条固定样本（会命中 Core IR 缓存），
 *                          测的是「命中缓存后的热路径延迟」。
 *   - 本脚本              = 容量实测：阶梯加压 + 每请求唯一源码（绕开 Core IR 缓存），
 *                          强制真实编译，直接压信号量 EVAL_SOURCE_PERMITS_COUNT。
 *
 * 被测瓶颈公式（PolicyEvaluationResource.java:100-105）：
 *   permits = min(2 × availableProcessors, maxHeapMb / 64)
 *   生产 ½核/384MB 堆 → min(2×1, 384/64) = min(2,6) = 2
 *   → 每 pod 只有 2 个编译槽位；第 3 个并发编译等 250ms 后返回 503 + Retry-After。
 *
 * 因此本脚本的「饱和信号」= 503 数量陡增。看到大量 503 即证实信号量是瓶颈。
 *
 * 前置：
 *   - ASTER_SECURITY_SIGNATURE_ENABLED=false（关 HMAC）
 *   - 【关键】ASTER_RATELIMIT_ENABLED=false（关限流，否则先撞限流墙）
 *   - 生产规格容器（0.5 CPU / -Xmx384m）
 *
 * 运行：
 *   API_BASE=http://localhost:8080 k6 run capacity-evaluate-source.js
 *   MAX_RPS=300 k6 run capacity-evaluate-source.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const API_BASE = __ENV.API_BASE || 'http://localhost:8080';
const TENANT_ID = __ENV.TENANT_ID || 'perf-tenant';
const MAX_RPS = parseInt(__ENV.MAX_RPS || '250', 10);
// 是否绕开 Core IR 缓存强制编译（true=压信号量真容量；false=测缓存命中路径）
const FORCE_COMPILE = (__ENV.FORCE_COMPILE || 'true') === 'true';

const evalDuration = new Trend('eval_duration', true);
const evalErrors = new Rate('eval_errors');
const c2xx = new Counter('status_2xx');
const c429 = new Counter('status_429'); // 限流墙（应为 0）
const c503 = new Counter('status_503'); // 信号量墙 —— 本测核心信号
const c5xx = new Counter('status_5xx'); // OOM（每次编译新建 Context 吃堆）
const cTimeout = new Counter('req_timeout');
const retryAfterSeen = new Counter('retry_after_seen'); // 503 带 Retry-After = 信号量拒绝的确证

// /evaluate-source 预期在 50-80 RPS/pod 饱和（cap=2），阶梯比 /evaluate 密。
function buildStages() {
  const targets = [20, 40, 60, 80, 100, 150, 200, 300].filter((r) => r <= MAX_RPS);
  const stages = [];
  for (const t of targets) {
    stages.push({ target: t, duration: '10s' });
    stages.push({ target: t, duration: '40s' });
  }
  return stages;
}

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 60,
      maxVUs: 400,
      stages: buildStages(),
      tags: { phase: 'ramp' },
    },
  },
  thresholds: {
    'eval_duration{phase:ramp}': ['p(99)<10000'], // 仅信息性
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// 基础模板：用唯一 threshold 常量制造不同源码，绕开 coreIrCache（键含源码 SHA-256）。
// 注意用当前有效的 CNL 完整语法（produce: / Return / at least）——终端的
// `amount > N.` 简写形式在当前 grammar 下已不解析（实测 col-N 语法错误）。
function makeSource(unique) {
  // 每请求换阈值数字 → 源码字符串不同 → coreIrCacheKey 不同 → 强制真实编译。
  return (
    `Module perf.cap${unique}.\n\n` +
    `Rule evaluate given amount, produce:\n` +
    `  If amount at least ${unique}\n` +
    `    Return true.\n` +
    `  Return false.`
  );
}

export default function () {
  // FORCE_COMPILE 时用 VU+iter 拼唯一数，绕缓存；否则固定源码走缓存命中路径。
  const unique = FORCE_COMPILE
    ? 1000 + ((__VU * 100000 + __ITER) % 900000)
    : 100;
  const source = makeSource(unique);
  const url = `${API_BASE}/api/v1/policies/evaluate-source`;
  const body = JSON.stringify({
    source,
    context: { amount: unique + 50 }, // 保证 amount > threshold，结果稳定为 true
    locale: 'en-US',
    functionName: 'evaluate',
  });
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': TENANT_ID,
    },
    tags: { name: 'evaluate_source' },
    timeout: '12s',
  };

  const res = http.post(url, body, params);
  evalDuration.add(res.timings.duration);

  if (res.status === 0) cTimeout.add(1);
  else if (res.status >= 200 && res.status < 300) c2xx.add(1);
  else if (res.status === 429) c429.add(1);
  else if (res.status === 503) {
    c503.add(1);
    // 信号量拒绝会带 Retry-After 头（PolicyEvaluationResource.java:428-457）
    if (res.headers['Retry-After']) retryAfterSeen.add(1);
  } else if (res.status >= 500) c5xx.add(1);

  const ok = check(res, {
    'status 2xx': (r) => r.status >= 200 && r.status < 300,
    'response has body': (r) => r.body && r.body.length > 0,
  });
  evalErrors.add(!ok);
}

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
  const nRetryAfter = m.retry_after_seen?.values?.count ?? 0;

  let diagnosis;
  if (n429 > 0) {
    diagnosis = '出现 429 → 限流没关！设 ASTER_RATELIMIT_ENABLED=false 重测';
  } else if (n503 > total * 0.02 && nRetryAfter > 0) {
    diagnosis = '大量 503+Retry-After → 信号量墙确证（cap=2/pod，符合公式 min(2×核,堆/64)）';
  } else if (n5xx > total * 0.02) {
    diagnosis = '5xx 为主 → 堆(384MB)瓶颈：每次编译新建 Context，OOM。可结合 podman stats 内存确认';
  } else if (!n503 && (v['p(99)'] ?? 0) < 500) {
    diagnosis = FORCE_COMPILE
      ? '未饱和 → 提高 MAX_RPS，或确认 FORCE_COMPILE=true（当前若缓存命中则压不到编译器）'
      : '缓存命中路径未饱和（FORCE_COMPILE=false，测的是热路径非编译容量）';
  } else {
    diagnosis = '接近饱和 → 看 JSON 分桶 p99 定位准确拐点 RPS';
  }

  const text = `
========================================================
aster-api /evaluate-source 容量实测（生产规格）
  强制编译=${FORCE_COMPILE} · 阶梯至 ${MAX_RPS} RPS · 限流应已关
========================================================
  总请求数:            ${total}
  达成吞吐 (均值):      ${(m.http_reqs?.values?.rate ?? 0).toFixed(0)} req/s

  状态码分布:
    2xx  (正常编译):    ${n2xx}
    429  (限流墙):      ${n429}   ${n429 > 0 ? '⚠ 限流没关' : ''}
    503  (信号量墙):    ${n503}   （其中带 Retry-After: ${nRetryAfter}）
    5xx  (OOM/错误):    ${n5xx}
    超时:               ${nTimeout}
    错误率:             ${errRate.toFixed(3)}%

  延迟 (ramp 阶段):
    P50:                ${v.med?.toFixed(2) ?? '?'}ms
    P95:                ${v['p(95)']?.toFixed(2) ?? '?'}ms
    P99:                ${v['p(99)']?.toFixed(2) ?? '?'}ms

  瓶颈诊断:
    ${diagnosis}

  ↓ 单 pod 编译吞吐上限 = 503 首次陡增前一个台阶的达成 2xx RPS。
    全系统 = 单 pod × pod 数（信号量是 per-pod，故此路径近似线性可加）。
========================================================
`;

  return {
    stdout: text,
    'evaluate-source-capacity-summary.json': JSON.stringify(data, null, 2),
  };
}
