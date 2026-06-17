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
     * #55: 单个 outbox 事件执行的最大阻塞等待时间。原先 {@code .await().indefinitely()}
     * 若下游 handler 永远不完成（如外部依赖挂死），会永久占用 outbox worker 线程。
     * 给一个充裕但有限的上限：超时即抛 {@code TimeoutException}，落入 FAILED 重试路径，
     * 而不是无声 wedge 整个调度循环。
     */
    private static final java.time.Duration OUTBOX_EVENT_TIMEOUT = java.time.Duration.ofSeconds(30);

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
            // #55: bound the blocking wait so a hung downstream handler cannot
            // pin the outbox worker thread forever. A stuck event now fails
            // (and is retried via the FAILED path) instead of wedging the loop.
            executeEvent(entity, payload)
                .replaceWithVoid()
                .await().atMost(OUTBOX_EVENT_TIMEOUT);

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
