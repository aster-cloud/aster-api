# aster-api 容量实测指南

> 用途：在**生产等价规格**下实测「满负荷吞吐上限」并定位瓶颈，验证容量推算。
> 这与 `README.md` 描述的 **回归检测**（固定 RPS 守阈值）是两回事 —— 见下表。

## 容量测试 vs 回归测试

| | 回归检测（既有 `evaluate.js` / `evaluate-source.js`） | 容量实测（本目录 `capacity-*.js`） |
|---|---|---|
| **目的** | 代码改动是否让延迟劣化（CI 门禁） | 系统满负荷能跑多少、卡在哪 |
| **负载模型** | 固定 RPS（500 / 50） | **阶梯递增 RPS 直到饱和** |
| **判定** | p95/p99 阈值 PASS/FAIL | 找 error/延迟拐点 = 饱和点 |
| **规格** | CI runner（2–5 核，随硬件） | **强制生产规格：0.5 核 / 384MB 堆** |
| **限流** | 保持默认 | CPU/信号量测试**关限流**；限流单独测 |

## 为什么必须锁定生产规格

既有实测数字（`docs/perf/*.txt`）都跑在 **2 核 / 1GB 堆**的机器上（README 已声明「绝对数字与生产硬件不同 → 回归检测，非 capacity planning」）。而生产 pod 只有：

| 参数 | 值 | 来源（k3s 仓库） |
|---|---|---|
| CPU limit | **500m（0.5 核）** | `apps/aster-lang/cloud/deployment.yaml:258` |
| JVM 堆 | **-Xmx384m** | `deployment.yaml:227` |
| 内存 limit | 512Mi | `deployment.yaml:257` |
| 副本 | 2（min）→ 6（max） | `hpa.yaml:33-34` |

`run-capacity.sh` 用 `podman --cpus=0.5 --memory=512m` + `JAVA_OPTS=-Xmx384m` 精确复现**单个生产 pod**，量出单 pod 上限后再外推到 HPA 副本数。

## 快速开始

```bash
# 前置：构建产物
./gradlew build -x test

# 一键：起生产规格容器 + 跑三组测试 + 抓服务端 CPU/内存
cd perf
./run-capacity.sh

# 结果落在 perf/capacity-results/
```

覆盖规格做对比实验（例如验证「CPU 翻倍吞吐是否线性上涨」这个扩容假设）：

```bash
CPU=1   HEAP=768 ./run-capacity.sh   # 1 核 / 768MB
CPU=0.5 HEAP=384 ./run-capacity.sh   # 生产基线
```

## 三个脚本各测什么瓶颈

| 脚本 | 端点 | 限流 | 验证的瓶颈 | 饱和信号 |
|---|---|---|---|---|
| `capacity-evaluate.js` | `/evaluate`（缓存命中） | **关** | **CPU**（纯 Truffle 计算，0 DB） | p99 陡增、无错误 |
| `capacity-evaluate-source.js` | `/evaluate-source`（强制编译） | **关** | **信号量 cap=2/pod** | 503 + Retry-After 陡增 |
| `capacity-ratelimit.js` | `/evaluate-source`（探针） | **开** | **限流 60/min/pod** | 429 陡增 |

### 瓶颈公式（代码依据）

- `/evaluate` 缓存命中 = 0 DB / 0 网络（`PolicyEvaluationService.java:181-193`）→ **纯 CPU-bound**，吞吐随 CPU 近似线性。
- `/evaluate-source` 信号量（`PolicyEvaluationResource.java:100-105`）：
  ```
  permits = min(2 × availableProcessors, maxHeapMb / 64)
  生产 = min(2×1, 384/64) = min(2, 6) = 2
  ```
  第 3 个并发编译等 250ms 后 503 + `Retry-After`（`PolicyEvaluationResource.java:433-447`）。
- 限流（`application.properties:361-362`）：60 请求/60 秒/key，**进程内非共享**（每 pod 独立），故 N pod = 60×N/min。

## 如何读结果 → 定位瓶颈

每个脚本的 `handleSummary` 已内置**自动诊断**，按状态码分布给结论。手动判读对照表：

| 观察到的现象 | 瓶颈 | 处置 |
|---|---|---|
| p99 陡增、错误率≈0、`podman stats` CPU≈100% | **CPU 饱和**（预期） | 提高 CPU limit（最高性价比） |
| 大量 **503 + Retry-After** | **信号量 cap=2** | 提高 CPU 或堆（抬高 permits） |
| 大量 **5xx**、`podman stats` 内存触顶 | **堆 384MB + 无界 CompiledPolicyCache** | 加 `CompiledPolicyCache` maximumSize / 提堆 |
| 出现 **429**（在关限流的测试里） | 限流没关成功 | 确认 `ASTER_RATELIMIT_ENABLED=false` |
| 客户端超时、k6 报 "insufficient VUs" | **压测客户端**瓶颈，非被测系统 | 调大脚本 `maxVUs` |

**关键交叉印证**：k6 只看客户端。务必同时看 `capacity-results/*-podman-stats.csv`（服务端 CPU/内存）。CPU 打满限额 = CPU-bound 确证；内存触顶 = 堆瓶颈确证。

## 从单 pod 外推到全系统

```
全系统吞吐 ≈ 单 pod 饱和 RPS × 有效 pod 数
```

- **`/evaluate`（缓存命中）**：近似线性可加（瓶颈是 per-pod CPU）。6 pod ≈ 单 pod × 6。
- **`/evaluate-source`**：近似线性可加（信号量是 per-pod）。
- **单租户吞吐**：受限流 = 60/min × pod 数（因限流非共享，请求需经 LB 均匀散到各 pod）。6 pod → 单租户 ≈ 360/min ≈ **21,600 次/小时**。

### ⚠️ 非线性因素（外推时必须打折）

1. **共享单实例 Postgres**（`postgres-cluster/cluster.yaml:15` instances=1，max_connections=100）。缓存命中路径不碰 DB，但**写路径**（创建/激活策略）和**冷路径**（新版本首次解析）会争抢连接池（8/pod × 6 = 48 < 50 角色上限）。高写入并发下 DB 是全系统非线性天花板。
2. **HPA 上限锁死在 6**（`hpa.yaml:13-14`，受单 pod 3Gi ephemeral-storage 限制）。突破需先上共享 PVC。
3. **限流非共享 + Cloudflare 回源**：`trust-forwarded-headers` 若没设 true，per-IP 限流会塌缩到 ingress IP（`application.properties:359` 注释）。

## 预期实测结论（供对照）

| 指标 | 推算值（按 2 核实测折算） | 实测将验证 |
|---|---|---|
| `/evaluate` 单 pod 饱和 | ~800–1000 RPS | 阶梯饱和点 |
| `/evaluate` 全系统（6 pod）/小时 | ~1,700 万次 | 单 pod × 6 |
| `/evaluate-source` 单 pod | ~50–80 RPS | 503 陡增前台阶 |
| 单租户/小时 | ~21,600 次 | ratelimit 脚本换算 |
| **头号瓶颈** | **CPU（½核）** | podman stats CPU≈100% |

跑完把实测数字回填到本表，替换推算值。

---

# 规模测试：多租户 × 多活跃策略极限（付费用户视角）

> 付费用户不受限流墙约束（限流只挡免费用户，付费=月限额）。所以「系统能撑多少
> 租户 × 每租户多少 active 策略」的真瓶颈是 **存储** 和 **内存**，不是限流。
> 本节工具关限流、真实播种海量策略、实测三堵墙。

## 工具

| 文件 | 作用 |
|---|---|
| `seed-scale.sh` | SQL `generate_series` 批量播种 N 租户 × M active 策略（真实 core_json，可 /evaluate 执行） |
| `scale-template.core.json` | 一个真实策略的 core_json 模板（播种时按 module 名替换） |
| `capacity-scale.js` | k6 高基数随机访问全 N×M 空间，区分内存墙 vs 冷路径吞吐墙 |

```bash
# 播种 500 租户 × 100 策略 = 50,000 active 策略
TENANTS=500 POLICIES_PER=100 ./seed-scale.sh
./seed-scale.sh --stats-only        # 看存储统计
./seed-scale.sh --truncate          # 清空重来

# 高基数访问测内存/吞吐墙（另开终端 jstat -gc <pid> 抓堆）
TENANTS=500 POLICIES_PER=100 RPS=400 DURATION=3m k6 run capacity-scale.js
```

## 实测结论（本地生产规格 -Xmx384m 真跑，非推算）

三堵墙，按先触顶排序：

### 墙 1（最先触顶）：内存 — 同时【热运行】的策略数

- **CompiledPolicyCache 是无界 ConcurrentHashMap 强引用**（`CompiledPolicyCache.java:20`），
  每个被访问过的策略常驻堆，无 TTL 无驱逐。
- **实测：9,707 个不同策略 → Old Gen 驻留 +142.8MB（full GC 后）= 15.06 KB/策略**
  （每条 = coreJson 字符串 + 元数据；串行低并发灌注 + jcmd GC.run 强制回收后 jstat 实测）。
- **内存墙 ≈ 19,700 个策略/pod 同时热运行**：`(384MB 堆 − 30MB 基线 − 64MB 工作集/GC 头空间) ÷ 15KB`。
- 6 pod 满负荷 → 全系统同时热运行上限 ≈ **~12 万个不同 active 策略**（每 pod ~2 万，缓存不跨 pod 共享）。
- ⚠️ 「热」= 被访问过且在缓存里。**冷躺 DB 的策略不占堆**，只占存储（见墙 3）。

### 墙 2：冷路径吞吐 — 首次访问大量不同策略的速率

- **实测：50,000 策略 @ 400 RPS 100% 冷访问 → 稳（0 错误，堆峰 240MB）；
  150,000 策略 @ 500 RPS 100% 冷访问 → 99.5% `RejectedExecutionException: Queue is full`。**
- 每个冷策略首次访问 = DB 版本解析（`requiringNew` 事务 + 1 JDBC 连接）+ 编译，占 worker 线程。
  100% 冷访问下，请求速率超过冷编译处理力 → 32 线程 / 512 队列（`application.properties:393,408`）溢出。
- **这不是「能存多少策略」的上限，是「多快能冷启动它们」的上限**。缓存预热 / 降 RPS 可缓解。
- 冷路径也争抢 8/pod JDBC 连接池 → 高基数突发时 DB 连接是次级约束。

### 墙 3（最晚触顶）：存储 — 冷存的策略总数

- **实测：50k 策略 = 79MB / 150k 策略 = 236MB → 稳定 ~1.65 KB/策略**
  （policy_versions + policy_catalog + 索引；core_json JSONB 压缩后 ~810 字节，content 占位 12 字节）。
- 生产共享 CNPG **20Gi**（authentik + grafana + aster_api 三库分），aster_api 可用约 **3–8Gi**。
- **存储墙 ≈ 200 万–500 万个策略**（`3–8Gi ÷ 1.65KB`），冷躺 DB。
- ⚠️ 真实策略 core_json 比本测模板大（模板是最简规则）；复杂策略按比例缩减，取 **~1.65KB × 复杂度倍数**。
- ⚠️ 版本永不 prune（不可变），存储随版本历史线性增长。

## 换算成「多少租户 × 每租户多少策略」

绑定约束是**墙 1（内存，同时热运行 ~2 万/pod，全系统 ~12 万）**，其次墙 3（存储 ~百万级冷存）。

| 场景 | 每租户活跃且高频的策略 | 结论 |
|---|---|---|
| 少策略高频（如 100/租户全热） | 100 | 内存墙 → 全系统 ~1,200 个租户可同时全热；存储可存百万级租户的冷策略 |
| 中等（如 1000/租户，10% 热） | 100 热 | 同上，~1,200 租户同时活跃；总注册租户受存储限 ~数千–数万 |
| 长尾（大量策略，少数热） | ~几十热 | 热运行不是瓶颈；存储墙主导 → **数万租户** |

**一句话**：单 pod 同时热运行 ~2 万策略（内存墙 15KB/策略 @ 384MB），6 pod ~12 万；冷存储上限 ~百万级策略（1.65KB/策略 @ 3-8Gi）。租户数无硬编码上限，实际受这两堵墙 + 每租户策略数分布决定，典型量级 **数千到数万租户**。

## 扩容杠杆（针对规模墙）

1. **给 `CompiledPolicyCache` 加 maximumSize + LRU**（当前无界，是内存墙 + OOM 风险根因）——最高优先级。
2. **提高 CPU/堆 limit**：堆翻倍→内存墙翻倍；CPU 翻倍→冷路径吞吐翻倍。
3. **预热缓存**（启动时批量编译热策略）绕开冷路径吞吐墙。
4. **Postgres 加副本 + 独立存储 + 版本归档**：突破存储墙 + 消除单点。

## 与 CI 的关系

本套脚本**不入 CI**（容量测试耗时、需专用规格容器 + 批量播种）。CI nightly 仍跑
`evaluate.js`/`evaluate-source.js` 做回归检测。容量/规模实测是**按需手动**跑（扩容决策前、规格变更后）。
