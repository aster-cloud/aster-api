# aster-api 性能基线

> P0-3 of phase4-p0-production-hardening.md

## 脚本

| 脚本 | 端点 | 度量 |
|------|------|------|
| `evaluate-source.js` | `POST /api/v1/policies/evaluate-source` | P50 / P95 / P99 latency + error rate |

## 验收阈值

- **P95 < 200ms**（基线，比 PM 02 的 P99<200ms 更激进）
- **5xx error rate < 0.1%**
- nightly 跑 5 分钟 sustained @ 50 RPS

## 本地跑

```bash
# 1) 启动本地 aster-api（禁用 HMAC 签名验证以便压测）
cd ~/IdeaProjects/aster-api
ASTER_SECURITY_SIGNATURE_ENABLED=false ./gradlew quarkusDev

# 2) 跑 k6
brew install k6
cd perf
API_BASE=http://localhost:8080 k6 run evaluate-source.js
```

## CI nightly

GitHub Actions `Nightly perf benchmark (k6)` 每天 04:00 UTC 自动跑。

设计权衡：**runner 内自包含**（aster-api + PG + Redis 启在 runner 临时网络），
不在 k3s 部署独立 perf-env。理由：
- k3s 资源已紧；perf-env 会推到 mem >85%
- runner 内启动可重复、隔离彻底、维护简单
- 缺点：绝对数字与生产硬件不同 → **用作回归检测**，不是 capacity planning

失败时阈值 violation 触发 Slack `SLACK_PERF_WEBHOOK` 通知。

## 路标

- [x] P0-3 基础版本：sustained 5min @ 50 RPS in CI
- [ ] P1: burst 测试（参考 aster-deploy/perf/k6-policy-evaluation.js）
- [ ] P1: AI SSE endpoint 长连接测试
- [ ] P3: 引入 perf-env k3s namespace 做生产硬件基线（资源宽裕时）
