package io.aster.billing.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 本地配额 snapshot 服务（aster-api Redis 后端）
 *
 * 角色：
 *   - evaluate hot path 的真源（cloud 不可达时仍准确）
 *   - 通过 cloud webhook 推送 + 启动 warm-up + 1h cron 对账保持新鲜
 *   - 配额计数 INCR 本地，同时双写到 cloud（SNAP-7）
 *
 * Redis key 设计：
 *   user:{userId}                       → JSON UserSnapshot   (TTL 1h)
 *   apikey:{keyHash}                    → JSON ApiKeySnapshot (TTL 1h)
 *   counter:user:{userId}:m:{period}    → INT  (无 TTL，月切时手动清)
 *
 * 命名空间前缀 `aq:` 避免与既有 PolicyCacheManager 等冲突。
 */
@ApplicationScoped
public class LocalQuotaSnapshotService {

    private static final Logger LOG = Logger.getLogger(LocalQuotaSnapshotService.class);
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(1);
    private static final String NS = "aq:";
    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

    @Inject
    Instance<RedisDataSource> redisDataSource;

    private boolean redisAvailable() {
        return redisDataSource.isResolvable() && !redisDataSource.isUnsatisfied();
    }

    private ValueCommands<String, String> strings() {
        return redisDataSource.get().value(String.class);
    }

    private KeyCommands<String> keys() {
        return redisDataSource.get().key(String.class);
    }

    // ============================================================
    // UserSnapshot
    // ============================================================

    public Optional<UserSnapshot> getUser(String userId) {
        if (!redisAvailable() || userId == null) return Optional.empty();
        try {
            String json = strings().get(NS + "user:" + userId);
            if (json == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(json, UserSnapshot.class));
        } catch (Exception e) {
            LOG.warnf("getUser redis read failed userId=%s: %s", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void setUser(UserSnapshot s) {
        if (!redisAvailable() || s == null || s.userId() == null) return;
        try {
            String json = MAPPER.writeValueAsString(s);
            strings().setex(NS + "user:" + s.userId(), SNAPSHOT_TTL.getSeconds(), json);
        } catch (Exception e) {
            LOG.warnf("setUser redis write failed userId=%s: %s", s.userId(), e.getMessage());
        }
    }

    public void invalidateUser(String userId) {
        if (!redisAvailable() || userId == null) return;
        try {
            keys().del(NS + "user:" + userId);
        } catch (Exception e) {
            LOG.warnf("invalidateUser failed userId=%s: %s", userId, e.getMessage());
        }
    }

    // ============================================================
    // ApiKeySnapshot
    // ============================================================

    public Optional<ApiKeySnapshot> getApiKey(String keyHash) {
        if (!redisAvailable() || keyHash == null) return Optional.empty();
        try {
            String json = strings().get(NS + "apikey:" + keyHash);
            if (json == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(json, ApiKeySnapshot.class));
        } catch (Exception e) {
            LOG.warnf("getApiKey redis read failed: %s", e.getMessage());
            return Optional.empty();
        }
    }

    public void setApiKey(String keyHash, ApiKeySnapshot s) {
        if (!redisAvailable() || keyHash == null || s == null) return;
        try {
            String json = MAPPER.writeValueAsString(s);
            strings().setex(NS + "apikey:" + keyHash, SNAPSHOT_TTL.getSeconds(), json);
        } catch (Exception e) {
            LOG.warnf("setApiKey redis write failed: %s", e.getMessage());
        }
    }

    public void invalidateApiKeysForUser(String userId) {
        // userId → keyHash 反向索引未持久化；当前实现：让 keyHash 自然 TTL 过期
        // 未来如需精确失效，加 SADD aq:user-keys:{userId} {keyHash} 索引
        if (!redisAvailable() || userId == null) return;
        // 占位：触发对账 cron 异步纠正（SNAP-6）
        LOG.debugf("invalidateApiKeysForUser userId=%s (lazy via TTL)", userId);
    }

    // ============================================================
    // 配额计数（INCR，月度）
    // ============================================================

    public long incrementCounter(String userId) {
        if (!redisAvailable() || userId == null) return 0L;
        try {
            String key = NS + "counter:user:" + userId + ":m:" + currentPeriod();
            return redisDataSource.get().value(String.class, Long.class)
                .incr(key);
        } catch (Exception e) {
            LOG.warnf("incrementCounter failed userId=%s: %s", userId, e.getMessage());
            return 0L;
        }
    }

    public long getCounter(String userId) {
        if (!redisAvailable() || userId == null) return 0L;
        try {
            String key = NS + "counter:user:" + userId + ":m:" + currentPeriod();
            String v = strings().get(key);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            LOG.warnf("getCounter failed userId=%s: %s", userId, e.getMessage());
            return 0L;
        }
    }

    public void resetCounter(String userId, String period) {
        if (!redisAvailable() || userId == null) return;
        try {
            keys().del(NS + "counter:user:" + userId + ":m:" + period);
        } catch (Exception e) {
            LOG.warnf("resetCounter failed: %s", e.getMessage());
        }
    }

    // ============================================================
    // Util
    // ============================================================

    public static String currentPeriod() {
        return YearMonth.now(ZoneOffset.UTC).toString(); // YYYY-MM
    }
}
