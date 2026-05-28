package io.aster.billing.snapshot;

import io.aster.billing.PlanGateConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.json.JsonArray;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Snapshot 预热 + 对账
 *
 * 触发：
 *   - StartupEvent：启动 30s 内拉一次全量，把 redis 写满
 *   - @Scheduled every="1h"：全量对账，把 redis 与 cloud 一致化（容灾兜底）
 *
 * 走 cloud /api/internal/snapshot/full?cursor=...&limit=1000，分页拉。
 */
@ApplicationScoped
public class SnapshotWarmupService {

    private static final Logger LOG = Logger.getLogger(SnapshotWarmupService.class);
    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 100; // 上限 100k user 防失控

    @Inject
    LocalQuotaSnapshotService snapshot;

    @Inject
    PlanGateConfig config;

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;
    // P0-R19: WebClient DCL consolidated into SharedWebClient

    // Shutdown latch: integration tests start the app briefly then exit.
    // The 2s warm-up delay below routinely outlives the test JVM's
    // Quarkus context, and the in-flight Vert.x dispatch then logs a
    // noisy RejectedExecutionException ("event executor terminated").
    // Latch is checked at every async hand-off so a late warm-up is a
    // silent no-op instead of a stack trace.
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    void onShutdown(@Observes ShutdownEvent ev) {
        shuttingDown.set(true);
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.enabled()) {
            LOG.info("plan-gate disabled, skipping snapshot warm-up");
            return;
        }
        // 异步启动 warm-up，不阻塞 readiness。
        //
        // 用独立 daemon 线程而不是 mutinyVertx.executeBlocking：后者完成时
        // 把 success 回 dispatch 到原 context 的 event loop —— 集成测试场景下，
        // event loop 在 2s warm-up 期间常常先被 Quarkus shutdown 关掉，
        // dispatch 失败时 Vert.x 把它当作 "Uncaught exception received by
        // Vert.x [Error Occurred After Shutdown]" 打印一长串 stack。
        //
        // daemon=true 让线程不阻碍 JVM 退出；fetchPage 自身在 shuttingDown
        // 设置后会快速失败，整体冷启动开销不变。
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000); // 给 redis / cloud 上线时间
                if (shuttingDown.get()) {
                    LOG.debug("snapshot warm-up skipped (shutdown in progress)");
                    return;
                }
                int n = fullSync("warmup");
                LOG.infof("snapshot warm-up complete: %d users", n);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (RejectedExecutionException e) {
                LOG.debug("snapshot warm-up aborted (event loop terminated during shutdown)");
            } catch (Exception e) {
                if (shuttingDown.get()) {
                    LOG.debug("snapshot warm-up failed during shutdown: " + e.getMessage());
                } else {
                    LOG.warnf("snapshot warm-up failed (will retry on 1h cron): %s", e.getMessage());
                }
            }
        }, "snapshot-warmup");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(every = "1h", delayed = "10m")
    void hourlyReconcile() {
        if (!config.enabled()) return;
        try {
            int n = fullSync("reconcile");
            LOG.infof("hourly snapshot reconcile: %d users", n);
        } catch (Exception e) {
            LOG.warnf("hourly reconcile failed: %s", e.getMessage());
        }
    }

    /**
     * 全量同步（分页）
     * @return 同步的 user 数量
     */
    public int fullSync(String reason) throws Exception {
        String cursor = null;
        int totalUsers = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonObject resp = fetchPage(cursor);
            JsonArray users = resp.getJsonArray("users", new JsonArray());
            JsonArray keys = resp.getJsonArray("apiKeys", new JsonArray());
            for (int i = 0; i < users.size(); i++) {
                JsonObject u = users.getJsonObject(i);
                snapshot.setUser(new UserSnapshot(
                    u.getString("userId"),
                    u.getString("plan", "free"),
                    u.getLong("apiCallsLimit", 0L),
                    u.getString("subscriptionStatus"),
                    u.getLong("aiBannedUntilEpochMs"),
                    u.getLong("gracePeriodEndsEpochMs")
                ));
                totalUsers++;
            }
            for (int i = 0; i < keys.size(); i++) {
                JsonObject k = keys.getJsonObject(i);
                String keyHash = k.getString("keyHash");
                if (keyHash == null) continue;
                ApiKeySnapshot s = k.getBoolean("valid", false)
                    ? new ApiKeySnapshot(true, null,
                        k.getString("apiKeyId"), k.getString("userId"),
                        k.getString("plan"), k.getLong("revokedAtEpochMs"))
                    : ApiKeySnapshot.invalid("revoked");
                snapshot.setApiKey(keyHash, s);
            }
            cursor = resp.getString("nextCursor");
            if (cursor == null) break;
        }
        LOG.infof("snapshot %s: synced %d users", reason, totalUsers);
        return totalUsers;
    }

    private JsonObject fetchPage(String cursor) throws Exception {
        if (shuttingDown.get()) {
            throw new RejectedExecutionException("snapshot warm-up aborted: shutdown in progress");
        }
        URI baseUri = URI.create(config.cloudInternalUrl());
        int port = baseUri.getPort() == -1
            ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
            : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());
        String path = "/api/internal/snapshot/full";
        StringBuilder query = new StringBuilder("limit=" + PAGE_LIMIT);
        if (cursor != null) {
            query.append("&cursor=").append(java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }

        long timestamp = System.currentTimeMillis() / 1000;
        String signature = config.hmacKey()
            .map(k -> sign(k, "GET\n" + path + "\n" + timestamp))
            .orElse("");

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        getClient()
            .get(port, baseUri.getHost(), path + "?" + query)
            .ssl(ssl)
            .timeout(10_000) // 全量拉相对慢，给 10s
            .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
            .putHeader("X-Aster-Signature", signature)
            .send()
            .onSuccess(resp -> {
                if (resp.statusCode() != 200) {
                    future.completeExceptionally(new RuntimeException(
                        "snapshot/full HTTP " + resp.statusCode()));
                    return;
                }
                try {
                    future.complete(resp.bodyAsJsonObject());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            })
            .onFailure(future::completeExceptionally);

        return future.get(15, TimeUnit.SECONDS);
    }

    private WebClient getClient() {
        return sharedWebClient.client();
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }
}
