package io.aster.audit.scheduler;

import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.entity.AnomalyActionPayload;
import io.aster.audit.outbox.GenericOutboxScheduler;
import io.aster.audit.service.AnomalyActionExecutor;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 异常动作消费器定时任务（Phase 3.7）
 *
 * 采用 GenericOutboxScheduler 提供的标准流程：
 * - 查询 PENDING 状态动作（批次=5）
 * - 设置 RUNNING → 调用执行器 → DONE / FAILED
 * - 每个动作使用独立事务，失败互不影响
 */
@ApplicationScoped
public class AnomalyActionScheduler extends GenericOutboxScheduler<AnomalyActionPayload, AnomalyActionEntity> {

    @Inject
    AnomalyActionExecutor executor;

    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void consumeActions() {
        processOutbox();
    }

    @Override
    protected Class<AnomalyActionEntity> getEntityClass() {
        return AnomalyActionEntity.class;
    }

    @Override
    protected Uni<?> executeEvent(AnomalyActionEntity entity, AnomalyActionPayload payload) {
        return switch (entity.actionType) {
            case "VERIFY_REPLAY" -> executor.executeReplayVerification(entity);
            case "AUTO_ROLLBACK" -> executor.executeAutoRollback(entity);
            default -> Uni.createFrom().failure(
                new IllegalArgumentException("未知的动作类型: " + entity.actionType)
            );
        };
    }

    @Override
    protected int batchSize() {
        return 5;
    }
}
