package io.aster.llm.cache;

import io.aster.llm.config.LlmConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 响应缓存
 *
 * 缓存策略：低温度(<=0.2) + 相同 prompt hash + 相同 schema 的结果可缓存。
 * 使用内存缓存，后续可接入 Redis。
 */
@ApplicationScoped
public class LlmResponseCache {

    private static final Logger LOG = Logger.getLogger(LlmResponseCache.class);

    @Inject
    LlmConfig config;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 查询缓存
     *
     * @return 缓存的响应，未命中返回 null
     */
    public String get(String tenantId, String model, String locale, String promptHash) {
        if (!config.cache().enabled()) return null;

        String key = buildKey(tenantId, model, locale, promptHash);
        CacheEntry entry = cache.get(key);

        if (entry == null) return null;

        if (System.currentTimeMillis() - entry.timestamp > config.cache().ttl().toMillis()) {
            cache.remove(key);
            return null;
        }

        LOG.debugf("缓存命中: key=%s", key);
        return entry.value;
    }

    /**
     * 写入缓存
     */
    public void put(String tenantId, String model, String locale, String promptHash, String value) {
        if (!config.cache().enabled()) return;

        // 限制缓存大小
        if (cache.size() >= config.cache().maxSize()) {
            evictOldest();
        }

        String key = buildKey(tenantId, model, locale, promptHash);
        cache.put(key, new CacheEntry(value, System.currentTimeMillis()));
        LOG.debugf("缓存写入: key=%s", key);
    }

    /**
     * 计算 prompt hash
     */
    public static String hashPrompt(String prompt) {
        // R30+ audit P2：原版 catch (Exception e) 把 NoSuchAlgorithmException
        // 当 routine fallback 处理，掩盖了"JVM 不支持 SHA-256"这种真实故障。
        // 改成只接 checked exception；任何运行时异常应当冒上去触发监控。
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(prompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 mandatory per JLS — 现代 JVM 不会到这里。落地 fallback
            // 加 warn 让 ops 看见，hash 退化但功能不挂。
            LOG.warnf("SHA-256 unavailable; falling back to hashCode-based key: %s", e.getMessage());
            return String.valueOf(prompt.hashCode());
        }
    }

    private String buildKey(String tenantId, String model, String locale, String promptHash) {
        return tenantId + ":" + model + ":" + locale + ":" + promptHash;
    }

    private void evictOldest() {
        // 简单清理：删除过期或最早的 10%
        long now = System.currentTimeMillis();
        long ttlMs = config.cache().ttl().toMillis();

        cache.entrySet().removeIf(entry -> now - entry.getValue().timestamp > ttlMs);

        if (cache.size() >= config.cache().maxSize()) {
            // 仍然满，清理最早的 10%
            int toRemove = cache.size() / 10;
            var iterator = cache.entrySet().iterator();
            for (int i = 0; i < toRemove && iterator.hasNext(); i++) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private record CacheEntry(String value, long timestamp) {}
}
