package io.aster.policy.security;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于滑动窗口的限流器
 *
 * 使用 ConcurrentHashMap + ConcurrentLinkedDeque 实现线程安全的滑动窗口计数。
 * 过期条目通过惰性清理机制移除，避免后台线程开销。
 */
@ApplicationScoped
public class RateLimiter {

    private static final Logger LOG = Logger.getLogger(RateLimiter.class);

    /**
     * 滑动窗口数据：key → 请求时间戳队列
     */
    private final ConcurrentHashMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    /**
     * 并发连接计数器：key → 当前活跃连接数
     */
    private final ConcurrentHashMap<String, AtomicInteger> connectionCounters = new ConcurrentHashMap<>();

    @ConfigProperty(name = "aster.ratelimit.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * 尝试获取一个请求许可
     *
     * @param identifier  限流标识（如租户ID、IP地址）
     * @param maxRequests 窗口内最大请求数
     * @param window      滑动窗口时长
     * @return true 如果请求被允许
     */
    public boolean tryAcquire(String identifier, int maxRequests, Duration window) {
        if (!enabled) {
            return true;
        }

        Deque<Instant> timestamps = windows.computeIfAbsent(identifier, k -> new ConcurrentLinkedDeque<>());
        Instant now = Instant.now();
        Instant windowStart = now.minus(window);

        // 移除过期的时间戳
        evictExpired(timestamps, windowStart);

        // 检查是否超限
        if (timestamps.size() >= maxRequests) {
            LOG.debugf("限流拒绝: identifier=%s, current=%d, max=%d", identifier, timestamps.size(), maxRequests);
            return false;
        }

        // 记录本次请求
        timestamps.addLast(now);
        return true;
    }

    /**
     * 查询剩余可用请求数
     *
     * @param identifier  限流标识
     * @param maxRequests 窗口内最大请求数
     * @param window      滑动窗口时长
     * @return 剩余可用请求数
     */
    public int remaining(String identifier, int maxRequests, Duration window) {
        if (!enabled) {
            return maxRequests;
        }

        Deque<Instant> timestamps = windows.get(identifier);
        if (timestamps == null) {
            return maxRequests;
        }

        Instant windowStart = Instant.now().minus(window);
        evictExpired(timestamps, windowStart);

        return Math.max(0, maxRequests - timestamps.size());
    }

    /**
     * 计算限流重置时间（Unix 秒）
     *
     * @param identifier 限流标识
     * @param window     滑动窗口时长
     * @return 最早条目过期时间的 Unix 秒，如果无条目则返回当前时间
     */
    public long resetAtEpochSecond(String identifier, Duration window) {
        Deque<Instant> timestamps = windows.get(identifier);
        if (timestamps == null || timestamps.isEmpty()) {
            return Instant.now().getEpochSecond();
        }

        Instant oldest = timestamps.peekFirst();
        if (oldest == null) {
            return Instant.now().getEpochSecond();
        }
        return oldest.plus(window).getEpochSecond();
    }

    /**
     * 尝试获取一个连接许可（并发连接限制）
     *
     * @param identifier     连接标识（如 IP）
     * @param maxConnections 最大并发连接数
     * @return true 如果连接被允许
     */
    public boolean tryAcquireConnection(String identifier, int maxConnections) {
        if (!enabled) {
            return true;
        }

        AtomicInteger counter = connectionCounters.computeIfAbsent(identifier, k -> new AtomicInteger(0));

        // CAS 循环确保线程安全
        while (true) {
            int current = counter.get();
            if (current >= maxConnections) {
                LOG.debugf("连接限流拒绝: identifier=%s, current=%d, max=%d", identifier, current, maxConnections);
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 释放一个连接许可
     *
     * @param identifier 连接标识
     */
    public void releaseConnection(String identifier) {
        AtomicInteger counter = connectionCounters.get(identifier);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /**
     * 获取当前活跃连接数
     *
     * @param identifier 连接标识
     * @return 当前活跃连接数
     */
    public int activeConnections(String identifier) {
        AtomicInteger counter = connectionCounters.get(identifier);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 移除滑动窗口中过期的时间戳
     *
     * 使用 pollFirst() 而非 iterator.remove()，利用 deque 的 FIFO 特性：
     * 最早的时间戳在队首，逐个弹出直到遇到未过期的条目。
     */
    private void evictExpired(Deque<Instant> timestamps, Instant windowStart) {
        while (true) {
            Instant head = timestamps.peekFirst();
            if (head == null || !head.isBefore(windowStart)) {
                break;
            }
            timestamps.pollFirst();
        }
    }

    /**
     * 定期清理已无活动记录的 idle 标识符，防止高基数流量导致内存无界增长。
     * 每 5 分钟执行一次：清除空的时间戳队列和零计数的连接计数器。
     */
    @Scheduled(every = "5m")
    void evictIdleEntries() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        windows.entrySet().removeIf(entry -> {
            Deque<Instant> q = entry.getValue();
            evictExpired(q, cutoff);
            return q.isEmpty();
        });
        connectionCounters.entrySet().removeIf(entry -> entry.getValue().get() == 0);
        LOG.debugf("限流器空闲清理完成: windows=%d, connections=%d", windows.size(), connectionCounters.size());
    }
}
