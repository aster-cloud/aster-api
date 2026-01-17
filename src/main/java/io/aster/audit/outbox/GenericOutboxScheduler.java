package io.aster.audit.outbox;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通用 Outbox Scheduler
 *
 * @param <P> payload 类型
 * @param <E> 实体类型
 */
public abstract class GenericOutboxScheduler<P, E extends GenericOutboxEntity<P>> {

    /**
     * 触发全量 Outbox 处理
     */
    public void processOutbox() {
        processOutbox(null);
    }

    /**
     * 按租户处理 Outbox，可用于测试或自定义调度策略
     *
     * @param tenantId 限定租户，null 表示全量
     */
    public void processOutbox(String tenantId) {
        List<Long> pendingIds = loadPendingEventIds(tenantId);
        if (pendingIds.isEmpty()) {
            Log.debugf("[%s] 无待处理事件", getEntityClass().getSimpleName());
            return;
        }

        Log.infof("[%s] 准备处理 %d 个事件 (tenant=%s)",
            getEntityClass().getSimpleName(),
            pendingIds.size(),
            tenantId == null ? "ALL" : tenantId
        );

        for (Long id : pendingIds) {
            try {
                QuarkusTransaction.requiringNew().run(() -> processSingleEvent(id));
            } catch (Exception e) {
                Log.errorf(e, "[%s] 事件 %d 处理失败", getEntityClass().getSimpleName(), id);
            }
        }
    }

    protected int batchSize() {
        return 5;
    }

    private List<Long> loadPendingEventIds(String tenantId) {
        EntityManager em = Panache.getEntityManager();

        StringBuilder jpql = new StringBuilder("SELECT e.id FROM ")
            .append(getEntityClass().getName())
            .append(" e WHERE e.status = :status");

        if (tenantId != null && !tenantId.isBlank()) {
            jpql.append(" AND e.tenantId = :tenant");
        }

        jpql.append(" ORDER BY e.createdAt ASC");

        var query = em.createQuery(jpql.toString(), Long.class)
            .setParameter("status", OutboxStatus.PENDING)
            .setMaxResults(batchSize());

        if (tenantId != null && !tenantId.isBlank()) {
            query.setParameter("tenant", tenantId);
        }

        return new ArrayList<>(query.getResultList());
    }

    private void processSingleEvent(Long eventId) {
        EntityManager em = Panache.getEntityManager();
        E entity = em.find(getEntityClass(), eventId, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            Log.warnf("[%s] 事件 %d 不存在，跳过", getEntityClass().getSimpleName(), eventId);
            return;
        }
        if (entity.status != OutboxStatus.PENDING) {
            Log.debugf("[%s] 事件 %d 状态=%s，跳过", getEntityClass().getSimpleName(), eventId, entity.status);
            return;
        }

        entity.status = OutboxStatus.RUNNING;
        entity.startedAt = Instant.now();
        entity.errorMessage = null;
        entity.persist();

        try {
            P payload = entity.deserializePayload();
            executeEvent(entity, payload)
                .replaceWithVoid()
                .await().indefinitely();

            entity.status = OutboxStatus.DONE;
            entity.completedAt = Instant.now();
            entity.persist();

            Log.infof("[%s] 事件 %d 执行完成", getEntityClass().getSimpleName(), eventId);
        } catch (Exception e) {
            entity.status = OutboxStatus.FAILED;
            entity.completedAt = Instant.now();
            entity.errorMessage = e.getMessage();
            entity.persist();

            Log.errorf(e, "[%s] 事件 %d 执行失败: %s",
                getEntityClass().getSimpleName(),
                eventId,
                Objects.toString(e.getMessage(), "n/a")
            );
        }
    }

    protected abstract Class<E> getEntityClass();

    protected abstract Uni<?> executeEvent(E entity, P payload);
}
