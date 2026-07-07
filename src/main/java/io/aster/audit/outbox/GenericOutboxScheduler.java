package io.aster.audit.outbox;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
     * #119：stale RUNNING 事件的回收超时。长事务拆分后（claim 短事务提交 RUNNING → handler
     * 无事务执行 → finalize 短事务标终态），若进程在 handler 执行中崩溃，事件会永久卡在 RUNNING
     * （不再像单事务那样随回滚自动回到 PENDING）。回收扫描把超过本阈值仍处于 RUNNING 的事件重置为
     * PENDING 以恢复 at-least-once 语义。
     *
     * <p>必须显著大于 {@link #OUTBOX_EVENT_TIMEOUT}（取其数倍），否则会在正常执行途中误回收——
     * 正常 handler 最多阻塞 30s 即会 finalize（成功 DONE 或超时 FAILED）。5 分钟为 30s 的 10 倍，
     * 足以吸收调度抖动、GC 停顿、DB 慢查询尾延迟。
     */
    private static final java.time.Duration RUNNING_RECLAIM_TIMEOUT = java.time.Duration.ofMinutes(5);

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
        // #119：先回收崩溃遗留的 stale RUNNING（拉回 PENDING），使其在本轮被重新领取。
        reclaimStaleRunning(tenantId);

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
            processSingleEvent(id);
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

    /**
     * 处理单个 outbox 事件（issue #119：拆分长事务为三段，缩短事务、让下游 {@code @Transactional}
     * 各自独立提交而非加入外层 outbox 事务）：
     * <ol>
     *   <li>事务A（{@link #claimEvent}）：领取 PENDING → RUNNING + 生成 lease token，短事务提交后释放行锁；</li>
     *   <li>无事务：执行 handler + 有界 await（下游 {@code @Transactional} 自管边界）；</li>
     *   <li>事务B（{@link #finalizeEvent}）：以 lease token 为条件写终态 DONE/FAILED。</li>
     * </ol>
     * 崩溃在第 2 段 → 事件卡 RUNNING，由 {@link #reclaimStaleRunning} 回收重投递。
     */
    private void processSingleEvent(Long eventId) {
        Claim claim;
        try {
            claim = QuarkusTransaction.requiringNew().call(() -> claimEvent(eventId));
        } catch (Exception e) {
            Log.errorf(e, "[%s] 事件 %d 领取失败", getEntityClass().getSimpleName(), eventId);
            return;
        }
        if (claim == null) {
            // 不存在或已被其他 pod 领取/推进（status != PENDING），跳过。
            return;
        }

        // 第 2 段：handler 执行 + 有界 await，无外层 outbox 事务。
        // #55: bound the blocking wait so a hung downstream handler cannot
        // pin the outbox worker thread forever.
        String errorMessage = null;
        boolean success;
        try {
            executeEvent(claim.entity, claim.payload)
                .replaceWithVoid()
                .await().atMost(OUTBOX_EVENT_TIMEOUT);
            success = true;
        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage();
            Log.errorf(e, "[%s] 事件 %d 执行失败: %s",
                getEntityClass().getSimpleName(),
                eventId,
                Objects.toString(e.getMessage(), "n/a")
            );
        }

        // 第 3 段：finalize 终态（独立短事务，以 lease token 判定 attempt 归属）。
        try {
            final boolean ok = success;
            final String err = errorMessage;
            QuarkusTransaction.requiringNew().run(
                () -> finalizeEvent(eventId, claim.leaseToken, ok, err));
        } catch (Exception e) {
            Log.errorf(e, "[%s] 事件 %d 标记终态失败（将由回收兜底重投递）",
                getEntityClass().getSimpleName(), eventId);
        }
    }

    /**
     * 事务A：领取事件。加 {@code PESSIMISTIC_WRITE} 行锁 + 双检 {@code status==PENDING} 保证多 pod
     * 下仅一个领取者推进（其余在锁上阻塞、拿锁后见状态已变而放弃）。领取时生成 lease token 并写入。
     *
     * @return 领取成功返回 {@link Claim}（含 detached entity 快照、payload、token）；否则 null。
     */
    private Claim claimEvent(Long eventId) {
        EntityManager em = Panache.getEntityManager();
        E entity = em.find(getEntityClass(), eventId, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            Log.warnf("[%s] 事件 %d 不存在，跳过", getEntityClass().getSimpleName(), eventId);
            return null;
        }
        if (entity.status != OutboxStatus.PENDING) {
            Log.debugf("[%s] 事件 %d 状态=%s，跳过", getEntityClass().getSimpleName(), eventId, entity.status);
            return null;
        }

        String token = UUID.randomUUID().toString();
        entity.status = OutboxStatus.RUNNING;
        entity.startedAt = Instant.now();
        entity.leaseToken = token;
        entity.errorMessage = null;
        entity.completedAt = null;
        entity.persist();

        // payload 在事务内（entity managed）反序列化；随后 entity 变 detached，
        // handler 仅只读其已加载标量字段（无 lazy 关联，见 AnomalyActionExecutor）。
        return new Claim(entity, entity.deserializePayload(), token);
    }

    /**
     * 事务B：以 lease token 为条件写终态。
     *
     * <p>守卫 {@code status==RUNNING AND leaseToken==本次 token}：若回收已把事件拉回 PENDING
     * 并被其他 attempt 重新领取（token 已变），本次迟到的 finalize 不得覆盖新 attempt 的状态（ABA）。
     */
    private void finalizeEvent(Long eventId, String leaseToken, boolean success, String errorMessage) {
        EntityManager em = Panache.getEntityManager();
        E entity = em.find(getEntityClass(), eventId, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            Log.warnf("[%s] 事件 %d finalize 时已不存在", getEntityClass().getSimpleName(), eventId);
            return;
        }
        if (entity.status != OutboxStatus.RUNNING || !Objects.equals(entity.leaseToken, leaseToken)) {
            // 事件已被回收/重投递（token 变）或已被别的路径推进——放弃本次 finalize。
            Log.warnf("[%s] 事件 %d finalize 令牌失配（status=%s），跳过（本次结果不落库）",
                getEntityClass().getSimpleName(), eventId, entity.status);
            return;
        }

        entity.status = success ? OutboxStatus.DONE : OutboxStatus.FAILED;
        entity.completedAt = Instant.now();
        entity.errorMessage = success ? null : errorMessage;
        entity.leaseToken = null;
        entity.persist();

        if (success) {
            Log.infof("[%s] 事件 %d 执行完成", getEntityClass().getSimpleName(), eventId);
        }
    }

    /**
     * 回收 stale RUNNING（issue #119）：把超过 {@link #RUNNING_RECLAIM_TIMEOUT} 仍处于 RUNNING 的
     * 事件重置为 PENDING 并清空 lease token，恢复被长事务拆分破坏的崩溃自动恢复（at-least-once）。
     * 幂等 UPDATE，多 pod 并发执行安全。已被重投递并 finalize 的旧 attempt 的迟到 finalize 会因
     * token 失配而被 {@link #finalizeEvent} 拒绝，故回收与迟到 finalize 不会互相破坏。
     */
    private void reclaimStaleRunning(String tenantId) {
        Instant cutoff = Instant.now().minus(RUNNING_RECLAIM_TIMEOUT).truncatedTo(ChronoUnit.MILLIS);
        try {
            int reclaimed = QuarkusTransaction.requiringNew().call(() -> {
                EntityManager em = Panache.getEntityManager();
                StringBuilder jpql = new StringBuilder("UPDATE ")
                    .append(getEntityClass().getName())
                    .append(" e SET e.status = :pending, e.leaseToken = null,")
                    .append(" e.errorMessage = :msg")
                    .append(" WHERE e.status = :running AND e.startedAt < :cutoff");
                if (tenantId != null && !tenantId.isBlank()) {
                    jpql.append(" AND e.tenantId = :tenant");
                }
                var query = em.createQuery(jpql.toString())
                    .setParameter("pending", OutboxStatus.PENDING)
                    .setParameter("running", OutboxStatus.RUNNING)
                    .setParameter("msg", "reclaimed after RUNNING timeout")
                    .setParameter("cutoff", cutoff);
                if (tenantId != null && !tenantId.isBlank()) {
                    query.setParameter("tenant", tenantId);
                }
                return query.executeUpdate();
            });
            if (reclaimed > 0) {
                Log.warnf("[%s] 回收 %d 个 stale RUNNING 事件（超 %s 未完成）→ 重置 PENDING",
                    getEntityClass().getSimpleName(), reclaimed, RUNNING_RECLAIM_TIMEOUT);
            }
        } catch (Exception e) {
            Log.errorf(e, "[%s] 回收 stale RUNNING 事件失败", getEntityClass().getSimpleName());
        }
    }

    protected abstract Class<E> getEntityClass();

    protected abstract Uni<?> executeEvent(E entity, P payload);

    /** claimEvent 的返回：领取到的 detached entity、反序列化 payload、本次 claim 的 lease token。 */
    private final class Claim {
        final E entity;
        final P payload;
        final String leaseToken;

        Claim(E entity, P payload, String leaseToken) {
            this.entity = entity;
            this.payload = payload;
            this.leaseToken = leaseToken;
        }
    }
}
