# Quarkus Native Image — Decision Record

> P0-7 of phase4-p0-production-hardening.md
> 决策日期：2026-05-12
> 决策人：Ryan

## 决策

**当前**：继续用 JVM build (`Dockerfile.jvm` → `wontlost/aster-api:jvm-latest`)。
**未来**：当满足触发条件之一时上 native（见下）。

---

## 背景

仓库里同时存在 `Dockerfile`（GraalVM native build）和 `Dockerfile.jvm`（JVM）。
`.github/workflows/deploy.yml` 用 `Dockerfile.jvm`。Native build 历史上配置过但
未投入生产。

## JVM 现状（已测量）

| 指标 | JVM (Quarkus fast-jar) |
|------|------------------------|
| Cold start | ~3-5 秒 |
| 镜像大小 | ~250MB (UBI8 OpenJDK base) |
| Pod RAM request | 384Mi / limit 768Mi |
| P95 latency（生产观测） | ~120ms（evaluate-source） |
| Build time（CI） | ~3-4 分钟（Gradle + Docker push） |

## Native 预估收益（基于 Quarkus 公开基准 + 项目特性）

| 指标 | Native (估计) | 与 JVM 差值 |
|------|---------------|-------------|
| Cold start | 0.05-0.5 秒 | **快 6-100×** |
| 镜像大小 | ~80-150MB | **小 40-60%** |
| Pod RAM | ~80-128Mi | **省 70%** |
| P95 latency（稳态） | ~150-200ms | **略慢 20-40%**（缺 JIT） |
| Build time（CI） | 8-15 分钟 | **慢 3-5×** |

## 项目特定 blockers

1. **Truffle / Polyglot 集成**：`org.graalvm.truffle:truffle-runtime` 依赖
   动态类加载 + 反射，native image 闭世界假设会破坏。当前 Dockerfile
   的 `--initialize-at-run-time` 列表只覆盖一个 logger，不足以涵盖 Truffle
   完整运行时。
2. **Vert.x WebClient（LLM 调用）**：动态代理 / SSL provider 需补全
   `reflect-config.json` 和 `resource-config.json`。
3. **Hibernate Reactive + Panache**：需要 `--initialize-at-build-time` 调优；
   Quarkus extension 已部分支持，但生产数据集多 entity 时容易漏配。
4. **Caffeine 缓存 + Jackson polymorphic**：反序列化路径有反射，需 hint。

预计补完上述 reflection / runtime-initialize 配置工作量：**8-15h**（首次落地）+
**每次新依赖 + 0.5-2h**（持续运维成本）。

## 收益判断

当前痛点排序：
1. ✅ **稳定性** — JVM 稳定，P95 latency 已经 < 200ms
2. ✅ **成本** — pod 单副本，4-node k3s 集群 RAM 没卡瓶颈
3. ⏸ **Cold start** — 单副本 + ArgoCD selfHeal，cold start 频率 < 1 次/月
4. ⏸ **镜像大小** — pull 时间不在关键路径（rollout 不阻塞流量，因为
   `imagePullPolicy: Always` 旧 pod 还在跑）

**结论**：当前 JVM 路径 ROI 高于 native。Native 的 cold start / 内存优势
在以下场景才显著：
- Serverless (knative / OpenFaaS)
- 频繁横向扩缩（auto-scale-to-zero）
- 单机多副本（每副本 RAM 节省累积）

我们都没有。

## 触发条件（重新评估）

满足任一时重启评估：
1. K3S RAM 紧到必须给 aster-api 减 200Mi 才能上新服务
2. ArgoCD selfHeal 频次 > 1 次/周（cold start 成本变可观）
3. Native image cold start 进一步降到 50ms 以内（GraalVM 25+ profile-guided
   opt 落地后值得再测）
4. Truffle 团队官方推荐 native image 配置成熟（关注
   https://www.graalvm.org/latest/reference-manual/embed-languages/）

## 不做的事

- 不在 P0 / P1 阶段花 8-15h 调 native build
- 不写 Dockerfile.native 蓝绿部署 manifest
- 保留现有 `Dockerfile` 不删除，但**不在 CI 中触发**，避免误以为它在跑

## 后续清理（可选）

如确认半年内不会评估 native：
- [ ] 删除 `Dockerfile` 和 `Dockerfile.musl`
- [ ] 删除 build.gradle 中的 native-image 相关任务定义
- [ ] 从 README 移除 native 指引（如有）

---

## 历史评估

| 日期 | 决策 | 理由 |
|------|------|------|
| 2026-05-12 | 不上 native | 现状无瓶颈、调优成本高、Truffle blocker |
