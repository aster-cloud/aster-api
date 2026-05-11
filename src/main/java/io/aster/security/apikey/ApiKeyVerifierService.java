package io.aster.security.apikey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aster.billing.PlanGateConfig;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * API key 验证服务
 *
 * 把客户传的 ak_xxx 明文 → SHA-256 hash → 调 cloud /api/internal/apikey/verify。
 * 命中结果缓存 5 min（与 plan-gate 一致）。
 *
 * Fail-open 策略：
 *   - cloud 不可达 + cache 命中 → 用缓存（旧但可信）
 *   - cloud 不可达 + cache miss → 拒绝（防伪造的新 key 蒙混过关）
 *
 * 缓存按 keyHash → ApiKeyVerifyResult 1:1，外加 userId → Set<keyHash> 反向索引
 * 用于 invalidateForUser（DUN-4 auto-downgrade 时清掉一个用户所有 key 缓存）。
 */
@ApplicationScoped
public class ApiKeyVerifierService {

    private static final Logger LOG = Logger.getLogger(ApiKeyVerifierService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int CACHE_MAX = 10_000;

    @Inject
    PlanGateConfig config;

    @Inject
    io.vertx.mutiny.core.Vertx mutinyVertx;

    @Inject
    io.aster.billing.snapshot.LocalQuotaSnapshotService localSnapshot;

    private volatile WebClient webClient;
    private Cache<String, ApiKeyVerifyResult> cache;
    /** userId → 该用户所有缓存过的 keyHash，用于按用户批量失效 */
    private final ConcurrentHashMap<String, Set<String>> userIndex = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        cache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX)
            .expireAfterWrite(CACHE_TTL)
            .removalListener((String keyHash, ApiKeyVerifyResult value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                if (value != null && value.userId() != null) {
                    Set<String> hashes = userIndex.get(value.userId());
                    if (hashes != null) hashes.remove(keyHash);
                }
            })
            .build();
        LOG.info("ApiKeyVerifierService started: cacheTtl=" + CACHE_TTL);
    }

    /**
     * 校验明文 API key（ak_xxx）是否有效
     *
     * @param plaintextKey 客户在 Authorization: Bearer 头里传的明文
     * @return 验证结果（缓存命中或新查询）
     */
    public ApiKeyVerifyResult verify(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return ApiKeyVerifyResult.invalid("empty");
        }
        if (!plaintextKey.startsWith("ak_")) {
            return ApiKeyVerifyResult.invalid("bad_format");
        }

        String keyHash = sha256Hex(plaintextKey);
        ApiKeyVerifyResult cached = cache.getIfPresent(keyHash);
        if (cached != null) {
            return cached;
        }

        // SNAP-5: Caffeine miss → 先查 redis snapshot（cloud webhook 推过来的）
        java.util.Optional<io.aster.billing.snapshot.ApiKeySnapshot> redisHit = localSnapshot.getApiKey(keyHash);
        if (redisHit.isPresent()) {
            io.aster.billing.snapshot.ApiKeySnapshot s = redisHit.get();
            ApiKeyVerifyResult fromRedis = s.valid()
                ? ApiKeyVerifyResult.valid(s.apiKeyId(), s.userId(), s.userId(), s.plan(), null)
                : ApiKeyVerifyResult.invalid(s.reason() != null ? s.reason() : "invalid");
            cache.put(keyHash, fromRedis);
            if (fromRedis.valid() && fromRedis.userId() != null) {
                userIndex.computeIfAbsent(fromRedis.userId(), k -> ConcurrentHashMap.newKeySet()).add(keyHash);
            }
            return fromRedis;
        }

        ApiKeyVerifyResult fresh;
        try {
            fresh = fetchFromCloud(keyHash);
        } catch (Exception e) {
            // cloud 不可达 + cache miss → 拒绝
            LOG.warnf("apikey verify cloud unreachable, denying: %s", e.getMessage());
            return ApiKeyVerifyResult.invalid("verify_unavailable");
        }
        cache.put(keyHash, fresh);
        if (fresh.valid() && fresh.userId() != null) {
            userIndex.computeIfAbsent(fresh.userId(), k -> ConcurrentHashMap.newKeySet()).add(keyHash);
        }
        return fresh;
    }

    /**
     * 让指定用户的所有 key 缓存失效（DUN-4 auto-downgrade 调用）
     */
    public int invalidateForUser(String userId) {
        if (userId == null) return 0;
        Set<String> hashes = userIndex.remove(userId);
        if (hashes == null || hashes.isEmpty()) return 0;
        int n = 0;
        for (String h : hashes) {
            cache.invalidate(h);
            n++;
        }
        return n;
    }

    private ApiKeyVerifyResult fetchFromCloud(String keyHash) throws Exception {
        URI baseUri = URI.create(config.cloudInternalUrl());
        int port = baseUri.getPort() == -1
            ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
            : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());
        String path = "/api/internal/apikey/verify";

        long timestamp = System.currentTimeMillis() / 1000;
        String signature = config.hmacKey()
            .map(k -> sign(k, "POST\n" + path + "\n" + timestamp))
            .orElse("");

        JsonObject body = new JsonObject().put("keyHash", keyHash);
        CompletableFuture<ApiKeyVerifyResult> future = new CompletableFuture<>();
        getClient()
            .post(port, baseUri.getHost(), path)
            .ssl(ssl)
            .timeout(config.requestTimeout().toMillis())
            .putHeader("X-Aster-Timestamp", String.valueOf(timestamp))
            .putHeader("X-Aster-Signature", signature)
            .putHeader("Content-Type", "application/json")
            .sendBuffer(body.toBuffer())
            .onSuccess(resp -> {
                if (resp.statusCode() != 200) {
                    future.completeExceptionally(new RuntimeException("verify HTTP " + resp.statusCode()));
                    return;
                }
                try {
                    JsonObject json = resp.bodyAsJsonObject();
                    boolean valid = json.getBoolean("valid", false);
                    if (!valid) {
                        future.complete(ApiKeyVerifyResult.invalid(json.getString("reason", "invalid")));
                    } else {
                        future.complete(ApiKeyVerifyResult.valid(
                            json.getString("apiKeyId"),
                            json.getString("userId"),
                            json.getString("tenantId"),
                            json.getString("plan"),
                            json.getString("subscriptionStatus")
                        ));
                    }
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
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
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}
