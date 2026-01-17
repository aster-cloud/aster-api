package io.aster.audit.inbox;

import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Inbox Guard 幂等性保护服务
 * 使用 Inbox 模式防止重复处理相同的幂等性键
 */
@ApplicationScoped
public class InboxGuard {

    @ConfigProperty(name = "inbox.ttl.days", defaultValue = "7")
    int ttlDays;

    @Inject
    EntityManager entityManager;

    /**
     * 尝试获取幂等性键（Inbox 模式核心方法）
     *
     * @param idempotencyKey 幂等性键
     * @param eventType 事件类型
     * @param tenantId 租户ID
     * @return 成功获取返回 true，重复返回 false
     */
    public Uni<Boolean> tryAcquire(String idempotencyKey, String eventType, String tenantId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            Log.warn("tryAcquire called with null/blank idempotencyKey, rejecting");
            return Uni.createFrom().item(false);
        }

        String normalizedKey = normalizeKey(idempotencyKey, tenantId);

        return InboxEvent.tryInsert(normalizedKey, eventType, tenantId)
            .map(event -> event != null)
            .onFailure().recoverWithItem(false);
    }

    /**
     * 阻塞版本的幂等性检查，用于 @Blocking 上下文（REST 端点）
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
                    "VALUES (:key, :eventType, :tenantId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (idempotency_key) DO NOTHING")
            .setParameter("key", normalizedKey)
            .setParameter("eventType", eventType)
            .setParameter("tenantId", storedTenant)
            .executeUpdate();
        return inserted > 0;
    }

    /**
     * 定时清理旧的 Inbox 事件（每 24 小时执行）
     * TTL 默认 7 天，可通过配置修改
     */
    @Scheduled(every = "24h", identity = "inbox-cleanup")
    @WithTransaction
    Uni<Void> scheduledCleanup() {
        Log.info("Starting scheduled inbox events cleanup");
        return InboxEvent.cleanupOldEvents(ttlDays)
            .invoke(count -> Log.infof("Cleaned up %d old inbox events (TTL: %d days)", count, ttlDays))
            .onFailure().invoke(ex -> Log.error("Failed to cleanup old inbox events", ex))
            .replaceWithVoid();
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

    private boolean isUniqueViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if (constraint != null && constraint.contains("inbox_events_pkey")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
