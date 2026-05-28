package io.aster.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 跨服务 plan 查询服务
 *
 * 流程：
 *   1. 优先读 Caffeine cache
 *   2. 未命中调 aster-cloud /internal/tenant/{id}/plan（HMAC 签名）
 *   3. 失败时按 failOpen 策略返回 PlanInfo.failOpen() 或抛异常
 *
 * 故障吸收：HTTP 异常或超时永远不会传播给业务侧；最差表现是 fail-open。
 */
@ApplicationScoped
public class PlanGateService {

    private static final Logger LOG = Logger.getLogger(PlanGateService.class);

    @Inject
    PlanGateConfig config;

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;

    private Cache<String, PlanInfo> cache;
    // P0-R19: WebClient DCL consolidated into SharedWebClient

    void onStart(@Observes StartupEvent ev) {
        cache = Caffeine.newBuilder()
            .maximumSize(config.cacheMaxEntries())
            .expireAfterWrite(config.cacheTtl())
            .build();
        if (!config.enabled()) {
            LOG.info("PlanGate 未启用：aster.plan-gate.enabled=false，所有调用按 Pro 档处理");
        } else {
            LOG.infof("PlanGate 启动：cloudUrl=%s, cacheTtl=%s",
                config.cloudInternalUrl(), config.cacheTtl());
        }
    }

    /** 查询某租户的 plan 信息（带 cache，故障 fail-open） */
    public PlanInfo lookupPlan(String tenantId) {
        if (!config.enabled()) {
            return PlanInfo.failOpen();
        }
        if (tenantId == null || tenantId.isBlank()) {
            return PlanInfo.failOpen();
        }

        PlanInfo cached = cache.getIfPresent(tenantId);
        if (cached != null) return cached;

        try {
            PlanInfo fetched = fetchFromCloud(tenantId);
            cache.put(tenantId, fetched);
            return fetched;
        } catch (Exception e) {
            LOG.warnf("PlanGate 查询失败 tenant=%s: %s（按 fail-open=%s 处理）",
                tenantId, e.getMessage(), config.failOpen());
            if (!config.failOpen()) {
                throw new PlanLimitException("plan_lookup_failed");
            }
            return PlanInfo.failOpen();
        }
    }

    /** 是否允许审批流（≥Pro 档） */
    public boolean allowsApproval(String tenantId) {
        return lookupPlan(tenantId).allowsApproval();
    }

    /** 单租户最大成员数（-1 表示无限） */
    public int maxTeamMembers(String tenantId) {
        return lookupPlan(tenantId).maxTeamMembers();
    }

    /** 让指定 tenant 的缓存立即失效（cloud 升级回调可调用此方法） */
    public void invalidate(String tenantId) {
        if (cache != null) {
            cache.invalidate(tenantId);
        }
    }

    private PlanInfo fetchFromCloud(String tenantId) throws Exception {
        URI baseUri = URI.create(config.cloudInternalUrl());
        int port = baseUri.getPort() == -1
            ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
            : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());
        String path = "/api/internal/tenant/" + tenantId + "/plan";

        long timestamp = System.currentTimeMillis() / 1000;
        String signature = config.hmacKey()
            .map(key -> sign(key, "GET\n" + path + "\n" + timestamp))
            .orElse("");

        CompletableFuture<PlanInfo> future = new CompletableFuture<>();
        getClient()
            .get(port, baseUri.getHost(), path)
            .ssl(ssl)
            .timeout(config.requestTimeout().toMillis())
            .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
            .putHeader("X-Aster-Signature", signature)
            .send()
            .onSuccess(resp -> {
                if (resp.statusCode() != 200) {
                    future.completeExceptionally(new RuntimeException(
                        "cloud /internal/tenant/" + tenantId + "/plan 返回 " + resp.statusCode()));
                    return;
                }
                try {
                    JsonObject json = resp.bodyAsJsonObject();
                    future.complete(toPlanInfo(json));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            })
            .onFailure(future::completeExceptionally);

        try {
            return future.get(config.requestTimeout().toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("plan-gate 调用超时", e);
        }
    }

    private static PlanInfo toPlanInfo(JsonObject json) {
        return new PlanInfo(
            json.getString("plan", "free"),
            json.getString("legacyTier", null),
            json.getBoolean("allowsApproval", false),
            json.getInteger("maxTeamMembers", 1),
            json.getLong("evaluationsLimit", 0L),
            json.getLong("apiCallsLimit", 0L)
        );
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

    private WebClient getClient() {
        return sharedWebClient.client();
    }
}
