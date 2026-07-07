package io.aster.audit.inbox;

import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Inbox Guard 幂等性保护服务
 * 使用 Inbox 模式防止重复处理相同的幂等性键。
 *
 * <p><b>#57 持久化模型统一</b>：本类此前混用 reactive（Hibernate Reactive Panache）与
 * blocking（JDBC EntityManager）两套实现对同一 inbox_events 表——是 audit 子系统唯一的
 * reactive 岛，也是"双池 100 连接"症状的来源。已统一到 <b>blocking</b>：全部 DB 操作走
 * blocking JDBC {@code @Transactional} + {@code EntityManager}，reactive 数据源随之被移除。
 * 对外仍暴露 {@code Uni} 接口（{@code tryAcquire}），但底层是 blocking 调用经 worker pool
 * offload——供 event-loop 上的 GraphQL 调用方无需改动线程模型即可安全使用。
 */
@ApplicationScoped
public class InboxGuard {

    @ConfigProperty(name = "inbox.ttl.days", defaultValue = "7")
    int ttlDays;

    @Inject
    EntityManager entityManager;

    /**
     * 尝试获取幂等性键（Inbox 模式核心方法，reactive 接口）。
     *
     * <p>底层是 blocking JDBC INSERT（{@link #tryAcquireBlocking}），经 worker pool offload
     * 后包装成 {@code Uni}，供 event-loop 上的 reactive 调用方（如 GraphQL createPolicy）安全调用
     * 而不阻塞事件循环。
     *
     * @param idempotencyKey 幂等性键
     * @param eventType 事件类型
     * @param tenantId 租户ID
     * @return 成功获取返回 true，重复返回 false
     */
    public Uni<Boolean> tryAcquire(String idempotencyKey, String eventType, String tenantId) {
        return Uni.createFrom()
            .item(() -> tryAcquireBlocking(idempotencyKey, eventType, tenantId))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 阻塞版本的幂等性检查，用于已在 worker 线程的上下文（@Blocking REST / @Scheduled outbox）。
     */
    @Transactional
    public boolean tryAcquireBlocking(String idempotencyKey, String eventType, String tenantId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            Log.warn("tryAcquireBlocking called with null/blank idempotencyKey, rejecting");
            return false;
        }
        String normalizedKey = normalizeKey(idempotencyKey, tenantId);
        String storedTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId.trim();
        int inserted = entityManager.createNativeQuery(
                "INSERT INTO inbox_events (idempotency_key, event_type, tenant_id, processed_at, created_at) " +
                    "VALUES (:key, :eventType, :tenantId, LOCALTIMESTAMP, LOCALTIMESTAMP) " +
                    "ON CONFLICT (idempotency_key) DO NOTHING")
            .setParameter("key", normalizedKey)
            .setParameter("eventType", eventType)
            .setParameter("tenantId", storedTenant)
            .executeUpdate();
        return inserted > 0;
    }

    /**
     * 定时清理旧的 Inbox 事件（每 24 小时执行）。TTL 默认 7 天，可通过配置修改。
     * blocking 实现（#57 统一）：@Transactional + EntityManager DELETE，跑在 @Scheduled worker 线程。
     */
    @Scheduled(every = "24h", identity = "inbox-cleanup",
               skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
    @Transactional
    void scheduledCleanup() {
        // 删除 processed_at 早于 (now - TTL) 的事件。cutoff 用 DB 端 LOCALTIMESTAMP 计算，
        // 与插入时的 LOCALTIMESTAMP 同源，避免 JVM 时区偏移。processed_at 是 TIMESTAMP
        // WITHOUT TIME ZONE，用 LOCALTIMESTAMP（同类型）而非 CURRENT_TIMESTAMP（timestamptz）
        // 消除隐式 cast 歧义（Codex #57 审查）。
        int deleted = entityManager.createNativeQuery(
                "DELETE FROM inbox_events WHERE processed_at < LOCALTIMESTAMP - make_interval(days => :days)")
            .setParameter("days", ttlDays)
            .executeUpdate();
        if (deleted > 0) {
            Log.infof("Cleaned up %d old inbox events (TTL: %d days)", deleted, ttlDays);
        }
    }

    /**
     * 组合租户与幂等性键，确保跨租户场景拥有独立命名空间
     */
    private String normalizeKey(String idempotencyKey, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return idempotencyKey;
        }
        return tenantId + ":" + idempotencyKey;
    }
}
