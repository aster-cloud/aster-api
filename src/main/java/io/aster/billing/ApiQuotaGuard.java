package io.aster.billing;

import io.opentelemetry.api.trace.Span;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Policy Execution API 的配额守门
 *
 * 三档行为（按 PM 取舍）：
 *   - 0%–100%   ：放行
 *   - 100%–110% ：放行 + soft warn 响应头（X-Quota-Warning）
 *   - 110%–200% ：放行（保 SLA），但记录用于后续催升级
 *   - >200%     ：429 hard reject
 *   - apiCallsLimit==0  → 直接 403（Free 用户）
 *   - apiCallsLimit==-1 → 永远放行（Enterprise）
 *
 * 调用次数记录由 recordAsync(...) 异步走，不阻塞热路径。
 */
@ApplicationScoped
public class ApiQuotaGuard {

    private static final Logger LOG = Logger.getLogger(ApiQuotaGuard.class);

    public enum Verdict {
        /** 放行（含未超限 / soft warn / overage 都标 ALLOW，由 detail 区分） */
        ALLOW,
        /** Free 档不开放 API */
        FORBIDDEN,
        /** 超 200% 月度配额 hard reject */
        RATE_LIMITED,
        /** per-second 限流（短期高频）— 客户端应该 retry-after */
        RATE_LIMITED_PER_SECOND
    }

    public record GuardResult(Verdict verdict, long limit, long used, int percent, String warning) {
        public boolean allowed() { return verdict == Verdict.ALLOW; }
    }

    @Inject
    PlanGateService planGateService;

    @Inject
    PlanGateConfig config;

    @Inject
    io.vertx.mutiny.core.Vertx mutinyVertx;

    @Inject
    io.aster.billing.snapshot.LocalQuotaSnapshotService snapshot;

    private volatile WebClient webClient;

    /**
     * 同步检查（< 5ms hot path）：本地 redis snapshot 优先
     *
     * SNAP-5 改造：
     *   1. 先查 redis user snapshot（命中 → 0 cloud RTT）
     *   2. miss → lazy fetch cloud /precheck + 写 redis（首次 + 降级路径）
     *   3. 月度计数从 redis counter:user:{id}:m:{period} 读（双写真源）
     */
    public GuardResult check(String tenantId, String userId) {
        // SNAP-9: 在当前 span 上打 tag，让 trace 在 Tempo 里能按用户筛选
        Span currentSpan = Span.current();
        if (currentSpan != null && userId != null) {
            currentSpan.setAttribute("aster.user_id", userId);
            currentSpan.setAttribute("aster.tenant_id", tenantId == null ? "" : tenantId);
        }
        // PlanGate 关闭时（aster.plan-gate.enabled=false，e.g. on-prem
        // 无 cloud BFF 或 loadtest 环境），整个配额体系跳过 — snapshot
        // 拿不到、cloud 拒连，再走下面任何分支只会换来一次 fail-open
        // 加几十毫秒延迟。直接放行 enterprise 等价物。
        if (!config.enabled()) {
            return new GuardResult(Verdict.ALLOW, -1, 0, 0, null);
        }

        long limit;
        long used;
        boolean apiAccessAllowed;
        boolean unlimited;
        java.util.Optional<io.aster.billing.snapshot.UserSnapshot> local = snapshot.getUser(userId);

        if (local.isPresent()) {
            if (currentSpan != null) {
                currentSpan.setAttribute("aster.quota.source", "local");
            }
            // Hot path: redis 命中
            io.aster.billing.snapshot.UserSnapshot s = local.get();
            limit = s.apiCallsLimit();
            apiAccessAllowed = s.apiAccessAllowed();
            unlimited = s.unlimitedApi();
            used = snapshot.getCounter(userId);
        } else {
            if (currentSpan != null) {
                currentSpan.setAttribute("aster.quota.source", "cloud_lazy");
            }
            // Cold path: lazy fetch cloud + 写 redis
            try {
                PrecheckResult pre = fetchPrecheck(userId);
                limit = pre.apiCallsLimit;
                used = pre.monthlyUsed;
                apiAccessAllowed = limit != 0;
                unlimited = limit == -1;
                // 写本地 redis 缓存（无 banned 信息时按未封禁处理）
                snapshot.setUser(new io.aster.billing.snapshot.UserSnapshot(
                    userId, pre.plan, pre.apiCallsLimit, null, null, null
                ));
            } catch (Exception e) {
                LOG.warnf("precheck 失败 + 本地无缓存，fail-open: %s", e.getMessage());
                PlanInfo fallback = PlanInfo.failOpen();
                return new GuardResult(Verdict.ALLOW, fallback.apiCallsLimit(), 0, 0, null);
            }
        }

        // Free 档（apiCallsLimit == 0）→ 403
        if (!apiAccessAllowed) {
            return new GuardResult(Verdict.FORBIDDEN, 0, 0, 0, null);
        }

        // Enterprise（-1）→ 永远放行
        if (unlimited) {
            return new GuardResult(Verdict.ALLOW, -1, 0, 0, null);
        }
        int percent = (int) ((used * 100) / Math.max(limit, 1));

        if (used >= limit * 2) {
            // 超 200% hard reject
            return new GuardResult(Verdict.RATE_LIMITED, limit, used, percent, null);
        }
        if (used >= limit) {
            // 100%-200% soft warn
            String warn = used >= (long) (limit * 1.1)
                ? "soft-overage"
                : "approaching-limit";
            return new GuardResult(Verdict.ALLOW, limit, used, percent, warn);
        }
        if (percent >= 80) {
            return new GuardResult(Verdict.ALLOW, limit, used, percent, "approaching-limit");
        }
        return new GuardResult(Verdict.ALLOW, limit, used, percent, null);
    }

    /**
     * 同步检查 per-API-key per-second 限流（< 5ms；Redis 不可达 fail-open）
     */
    public RateCheck checkRate(String tenantId, String apiKeyId) {
        if (apiKeyId == null || apiKeyId.isBlank()) {
            // 无 API key（UI 路径）→ 不参与 per-key 限流
            return new RateCheck(true, 0, 0);
        }
        String plan;
        try {
            plan = planGateService.lookupPlan(tenantId).plan();
        } catch (Exception e) {
            return new RateCheck(true, 0, 0); // fail-open
        }

        try {
            URI baseUri = URI.create(config.cloudInternalUrl());
            int port = baseUri.getPort() == -1
                ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
                : baseUri.getPort();
            boolean ssl = "https".equals(baseUri.getScheme());
            String path = "/api/internal/api/rate-check";

            JsonObject body = new JsonObject()
                .put("apiKeyId", apiKeyId)
                .put("plan", plan);

            long timestamp = System.currentTimeMillis() / 1000;
            String signature = config.hmacKey()
                .map(k -> sign(k, "POST\n" + path + "\n" + timestamp))
                .orElse("");

            CompletableFuture<RateCheck> future = new CompletableFuture<>();
            getClient()
                .post(port, baseUri.getHost(), path)
                .ssl(ssl)
                .timeout(Math.min(500, config.requestTimeout().toMillis()))
                .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
                .putHeader("X-Aster-Signature", signature)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(body.toBuffer())
                .onSuccess(resp -> {
                    if (resp.statusCode() != 200) {
                        future.complete(new RateCheck(true, 0, 0));
                        return;
                    }
                    try {
                        JsonObject json = resp.bodyAsJsonObject();
                        future.complete(new RateCheck(
                            json.getBoolean("allowed", true),
                            json.getInteger("used", 0),
                            json.getInteger("limit", 0)
                        ));
                    } catch (Exception ex) {
                        future.complete(new RateCheck(true, 0, 0));
                    }
                })
                .onFailure(err -> future.complete(new RateCheck(true, 0, 0)));

            return future.get(700, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return new RateCheck(true, 0, 0);
        }
    }

    public record RateCheck(boolean allowed, int used, int limit) {}

    /**
     * 异步记录一次调用 — fire-and-forget，不阻塞业务路径
     */
    public void recordAsync(String userId, String tenantId, String apiKeyId,
                             String endpointPath, String status, long latencyMs) {
        // PlanGate 关闭：跳过本地计数 + cloud 异步上报。无 cloud 接收方时
        // 这两步纯粹是 DNS lookup + connection refused 的开销。
        if (!config.enabled()) {
            return;
        }
        // SNAP-5 双写步骤 1：本地 redis INCR（同步，hot path 真源，0 RTT）
        // 仅 success 计数（与 cloud apiCallRecords 表 status='success' 过滤一致）
        if ("success".equals(status) && userId != null) {
            try {
                snapshot.incrementCounter(userId);
            } catch (Exception e) {
                LOG.warnf("local counter incr failed userId=%s: %s", userId, e.getMessage());
            }
        }

        // SNAP-5 双写步骤 2：异步推 cloud（fire-and-forget，dashboard 显示用 + 跨 pod 对账）
        try {
            URI baseUri = URI.create(config.cloudInternalUrl());
            int port = baseUri.getPort() == -1
                ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
                : baseUri.getPort();
            boolean ssl = "https".equals(baseUri.getScheme());
            String path = "/api/internal/api/usage";

            JsonObject body = new JsonObject()
                .put("userId", userId)
                .put("tenantId", tenantId)
                .put("apiKeyId", apiKeyId)
                .put("endpointPath", endpointPath)
                .put("status", status)
                .put("latencyMs", latencyMs);

            long timestamp = System.currentTimeMillis() / 1000;
            String signature = config.hmacKey()
                .map(k -> sign(k, "POST\n" + path + "\n" + timestamp))
                .orElse("");

            getClient()
                .post(port, baseUri.getHost(), path)
                .ssl(ssl)
                .timeout(config.requestTimeout().toMillis())
                .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
                .putHeader("X-Aster-Signature", signature)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(body.toBuffer())
                .onFailure(err -> LOG.warnf("api usage record failed: user=%s, err=%s",
                    userId, err.getMessage()));
        } catch (Exception e) {
            LOG.warnf("api usage record exception: user=%s, err=%s", userId, e.getMessage());
        }
    }

    /**
     * AKA-8: 合并查询 — 一次 GET 拿 plan + apiCallsLimit + monthlyUsed
     */
    private record PrecheckResult(long apiCallsLimit, long monthlyUsed, String plan) {}

    private PrecheckResult fetchPrecheck(String userId) throws Exception {
        URI baseUri = URI.create(config.cloudInternalUrl());
        int port = baseUri.getPort() == -1
            ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
            : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());
        String path = "/api/internal/api/precheck";
        String query = "userId=" + urlEncode(userId);

        long timestamp = System.currentTimeMillis() / 1000;
        String signature = config.hmacKey()
            .map(k -> sign(k, "GET\n" + path + "\n" + timestamp))
            .orElse("");

        CompletableFuture<PrecheckResult> future = new CompletableFuture<>();
        getClient()
            .get(port, baseUri.getHost(), path + "?" + query)
            .ssl(ssl)
            .timeout(config.requestTimeout().toMillis())
            .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
            .putHeader("X-Aster-Signature", signature)
            .send()
            .onSuccess(resp -> {
                if (resp.statusCode() != 200) {
                    future.completeExceptionally(new RuntimeException(
                        "precheck 返回 " + resp.statusCode()));
                    return;
                }
                try {
                    JsonObject json = resp.bodyAsJsonObject();
                    future.complete(new PrecheckResult(
                        json.getLong("apiCallsLimit", 0L),
                        json.getLong("monthlyUsed", 0L),
                        json.getString("plan", "free")
                    ));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            })
            .onFailure(future::completeExceptionally);

        return future.get(config.requestTimeout().toMillis() + 500, TimeUnit.MILLISECONDS);
    }

    private WebClient getClient() {
        if (webClient == null) {
            synchronized (this) {
                if (webClient == null) {
                    webClient = WebClient.create(mutinyVertx.getDelegate());
                }
            }
        }
        return webClient;
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
