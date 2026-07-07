/**
 * aster-api 规模/内存墙实测（多租户 × 多活跃策略，关限流）
 *
 * 回答：付费用户不受限流约束下，系统能【同时热运行】多少个不同的 active 策略？
 *
 * 机制（源码依据）：
 *   - CompiledPolicyCache 是【无界】ConcurrentHashMap（CompiledPolicyCache.java:20），
 *     每个被访问过的 (tenant,module,function,versionId) 编译产物常驻 384MB 堆，无 TTL 无驱逐。
 *   - policy-results 结果缓存仅 4096/pod（application.properties:43），跨所有租户共享。
 *   - CoreIR/Source 缓存各 2048，超限【整表清空】（DynamicCnlExecutor.java:324,524）——容量悬崖。
 *   本脚本用【高基数随机访问】遍历 N×M 策略空间：每个首次访问=冷路径编译并常驻堆，
 *   持续访问不同策略 → 堆单调增长 → 找 OOM 点 = 同时热运行策略上限。
 *
 * 前置：
 *   - 已用 seed-scale.sh 播种 N 租户 × M 策略（策略命名 scale-t<NNNNN> / scale.m<t>_<seq>）
 *   - 关限流：ASTER_RATELIMIT_ENABLED=false（付费用户不受限流，且规模测试要打满）
 *   - 生产规格容器 -Xmx384m（run-capacity.sh）——堆大小决定 OOM 点
 *
 * 运行：
 *   TENANTS=500 POLICIES_PER=100 API_BASE=http://localhost:8080 k6 run capacity-scale.js
 *   # 同时另开终端抓内存：watch -n2 'podman stats --no-stream <api容器>'
 *
 * 如何读结果：
 *   - 若跑完无 5xx、内存稳定 → 该基数(N×M)未触内存墙，堆够用；加大规模继续。
 *   - 若出现 5xx 陡增 + podman 内存触顶 512Mi → 达到 CompiledPolicyCache OOM 墙，
 *     记下【已访问的不同策略数】= 单 pod 同时热运行上限。
 *   - 缓存命中率（cache_hit_ratio）随基数上升而下降：命中率跌到低位=结果缓存 4096 被高基数冲垮。
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const API_BASE = __ENV.API_BASE || 'http://localhost:8080';
const TENANTS = parseInt(__ENV.TENANTS || '500', 10);
const POLICIES_PER = parseInt(__ENV.POLICIES_PER || '100', 10);
// 目标 RPS 与持续时间：默认稳态高基数灌注，逐步填满编译缓存。
const RPS = parseInt(__ENV.RPS || '300', 10);
const DURATION = __ENV.DURATION || '5m';

const evalDuration = new Trend('eval_duration', true);
const evalErrors = new Rate('eval_errors');
const cacheMiss = new Rate('cold_ratio'); // 近似冷访问比例（首次访问该策略）
const c2xx = new Counter('status_2xx');
const c5xx = new Counter('status_5xx'); // 5xx 总数
const cOOMlike = new Counter('oom_like'); // 5xx 且 body 含内存/OOM 线索 → 内存墙
const queueFull = new Counter('queue_full'); // 5xx 且 body 含 queue/rejected → 冷路径吞吐墙

// 客户端侧「已见策略」集合，估算冷/热比例（服务端无法直接暴露）。
const seen = new Set();

export const options = {
  scenarios: {
    // 稳态高基数灌注：持续按 RPS 随机打 N×M 空间，堆随不同策略累积增长。
    saturate: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(200, RPS),
      maxVUs: Math.min(600, RPS * 3),
    },
  },
  thresholds: {
    'eval_duration': ['p(99)<5000'], // 信息性；规模测试不设 PASS/FAIL
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// 随机选一个 (tenant, module) —— 覆盖全 N×M 空间制造高基数。
// 播种命名：租户 scale-t00001..，module scale.m<t>_<seq>（t=1..TENANTS, seq=1..POLICIES_PER）
function pick(iterIndex) {
  // 用确定性伪随机（避免 Math.random，k6 里可用但为可复现改用 iter 派生）。
  const t = 1 + ((iterIndex * 2654435761) % TENANTS);
  const seq = 1 + ((iterIndex * 40503) % POLICIES_PER);
  return {
    tenant: `scale-t${String(t).padStart(5, '0')}`,
    module: `scale.m${t}_${seq}`,
    key: `${t}:${seq}`,
  };
}

let counter = 0;
export default function () {
  const idx = counter++;
  const p = pick(idx);
  const isCold = !seen.has(p.key);
  if (isCold) seen.add(p.key);
  cacheMiss.add(isCold);

  const res = http.post(
    `${API_BASE}/api/v1/policies/evaluate`,
    JSON.stringify({
      policyModule: p.module,
      policyFunction: 'evaluate',
      context: [150], // 位置参数数组（实测正确格式）；amount=150 → true
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Tenant-Id': p.tenant,
        'X-User-Role': 'admin',
        'X-User-Id': 'scale-runner',
      },
      tags: { name: 'evaluate_scale' },
      timeout: '15s',
    },
  );

  evalDuration.add(res.timings.duration);
  if (res.status >= 200 && res.status < 300) c2xx.add(1);
  else if (res.status >= 500) {
    c5xx.add(1);
    const b = (res.body || '').toLowerCase();
    // 区分内存墙(OOM/heap) vs 冷路径吞吐墙(queue full/RejectedExecution)。
    // 100% 冷访问下每请求都要 DB 解析+编译占 worker 线程，队列(512)易满 → 吞吐墙非内存墙。
    if (b.includes('memory') || b.includes('heap') || b.includes('oom')) cOOMlike.add(1);
    if (b.includes('queue') || b.includes('rejected')) queueFull.add(1);
  }
  const ok = check(res, { 'status 2xx': (r) => r.status >= 200 && r.status < 300 });
  evalErrors.add(!ok);
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = m.eval_duration?.values ?? {};
  const total = m.http_reqs?.values?.count ?? 0;
  const n2xx = m.status_2xx?.values?.count ?? 0;
  const n5xx = m.status_5xx?.values?.count ?? 0;
  const nOOM = m.oom_like?.values?.count ?? 0;
  const nQueue = m.queue_full?.values?.count ?? 0;
  const coldRatio = (m.cold_ratio?.values?.rate ?? 0) * 100;
  const errRate = (m.eval_errors?.values?.rate ?? 0) * 100;
  const cardinality = TENANTS * POLICIES_PER;

  let diagnosis;
  if (nQueue > n5xx * 0.5) {
    diagnosis =
      `5xx 以 Queue-full/RejectedExecution 为主（${nQueue}/${n5xx}）→ 撞【冷路径吞吐墙】非内存墙：` +
      `100% 冷访问下每请求 DB 解析+编译占 worker 线程，${RPS} RPS 超过冷编译处理力→线程池队列(512)满。` +
      `降低 RPS 或预热缓存可缓解。这不是"能存多少策略"的上限，是"多快能冷启动它们"的上限。`;
  } else if (nOOM > 0 || n5xx > total * 0.02) {
    diagnosis =
      `5xx 陡增（${n5xx}，含内存类 ${nOOM}）→ 疑似 CompiledPolicyCache 无界填满 384MB 堆触内存墙。` +
      `已覆盖基数≈${cardinality}。结合 podman stats / JVM heap metrics 确认 Old Gen 是否触顶。`;
  } else if (errRate < 1) {
    diagnosis =
      `无内存墙迹象（错误率 ${errRate.toFixed(2)}%）→ 当前基数 ${cardinality} 策略堆能扛住。` +
      `加大 TENANTS×POLICIES_PER 继续逼近 OOM，或看 podman stats 内存斜率外推。`;
  } else {
    diagnosis = `有少量错误（${errRate.toFixed(2)}%）→ 检查是否 DB 连接池饱和（高基数冷路径争用）或临近内存墙。`;
  }

  const text = `
========================================================
aster-api 规模/内存墙实测（多租户 × 多活跃策略）
  基数：${TENANTS} 租户 × ${POLICIES_PER} 策略 = ${cardinality} 个 active 策略
  ${RPS} RPS × ${DURATION} · 关限流
========================================================
  总请求:              ${total}
  2xx:                 ${n2xx}
  5xx (总):            ${n5xx}   （内存类 OOM: ${nOOM} · 队列满/吞吐墙: ${nQueue}）
  错误率:              ${errRate.toFixed(3)}%
  冷访问比例(客户端估): ${coldRatio.toFixed(1)}%  （越高=越多首次编译入堆）

  延迟:
    P50:                ${v.med?.toFixed(2) ?? '?'}ms
    P95:                ${v['p(95)']?.toFixed(2) ?? '?'}ms
    P99:                ${v['p(99)']?.toFixed(2) ?? '?'}ms
    max:                ${v.max?.toFixed(2) ?? '?'}ms   ← 冷路径编译尖峰

  诊断:
    ${diagnosis}

  ↓ 同时热运行策略上限 = OOM 前已访问的不同策略数（配合 podman stats 内存曲线）。
    存储上限另见 seed-scale.sh 的 bytes_per_policy 外推（冷躺 DB，不占堆）。
========================================================
`;

  return {
    stdout: text,
    'scale-capacity-summary.json': JSON.stringify(data, null, 2),
  };
}
