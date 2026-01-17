package io.aster.audit.service;

import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.entity.AnomalyActionPayload;
import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.inbox.InboxGuard;
import io.aster.audit.metrics.AnomalyMetrics;
import io.aster.audit.rest.model.VerificationResult;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.service.PolicyVersionService;
import io.aster.workflow.WorkflowSchedulerService;
import io.aster.workflow.WorkflowStateEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 异常响应动作执行器（Phase 3.7）
 *
 * 采用 orchestration 模式调用现有服务：
 * - Replay 验证：调用 WorkflowSchedulerService.processWorkflow
 * - 自动回滚：调用 PolicyVersionService.rollbackToVersion
 * - 不重新实现业务逻辑，仅作为编排层
 */
@ApplicationScoped
public class AnomalyActionExecutor {

    private static final Logger LOG = Logger.getLogger(AnomalyActionExecutor.class);

    @Inject
    WorkflowSchedulerService workflowScheduler;

    @Inject
    PolicyVersionService policyVersionService;

    @Inject
    AnomalyWorkflowService anomalyWorkflowService;

    @Inject
    InboxGuard inboxGuard;

    @Inject
    AnomalyMetrics anomalyMetrics;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * 执行 Replay 验证
     *
     * 流程：
     * 1. 从 payload 提取 workflowId
     * 2. 查询 WorkflowStateEntity，检查 clockTimes
     * 3. 调用 WorkflowSchedulerService.processWorkflow 触发 Replay
     * 4. 比对原始执行与 Replay 结果
     * 5. 构建 VerificationResult
     * 6. 调用 AnomalyWorkflowService.recordVerificationResult
     *
     * @param action 异常动作实体
     * @return 验证结果
     */
    @Transactional
    public Uni<VerificationResult> executeReplayVerification(AnomalyActionEntity action) {
        String idempotencyKey = String.format("REPLAY_%s_%s", action.anomalyId, action.id);
        boolean acquired = inboxGuard.tryAcquireBlocking(idempotencyKey, "VERIFY_REPLAY", resolveInboxTenant(action));
        if (!acquired) {
            LOG.infof("检测到重复 Replay 验证请求，跳过执行：%s", idempotencyKey);
            return Uni.createFrom().nullItem();
        }
        AnomalyActionPayload payload = action.deserializePayload();
        return performReplayVerification(action, payload);
    }

    private Uni<VerificationResult> performReplayVerification(AnomalyActionEntity action, AnomalyActionPayload payload) {
        try {
            // 1. 从 payload 提取 workflowId
            UUID workflowId = payload.workflowId();
            if (workflowId == null) {
                return Uni.createFrom().failure(
                    new IllegalArgumentException("Payload 缺少 workflowId")
                );
            }

            // 2. 查询原始 WorkflowStateEntity
            WorkflowStateEntity originalWorkflow = WorkflowStateEntity.findByWorkflowId(workflowId)
                .orElse(null);

            if (originalWorkflow == null) {
                return Uni.createFrom().failure(
                    new IllegalArgumentException("Workflow 不存在: workflowId=" + workflowId)
                );
            }

            // 检查是否有 clockTimes（确定性时间记录）
            if (originalWorkflow.clockTimes == null || originalWorkflow.clockTimes.isBlank()) {
                LOG.warnf("Workflow %s 没有 clockTimes，降级为普通执行", workflowId);
                // 对于没有 clockTimes 的旧 workflow，返回无法验证的结果
                VerificationResult result = new VerificationResult(
                    false,
                    null,
                    workflowId.toString(),
                    Instant.now(),
                    originalWorkflow.durationMs,
                    null
                );
                return Uni.createFrom().item(result);
            }

            // 3. 记录原始执行结果
            String originalStatus = originalWorkflow.status;
            Long originalDurationMs = originalWorkflow.durationMs;
            String originalErrorMessage = originalWorkflow.errorMessage;

            LOG.infof("开始 Replay 验证: workflowId=%s, originalStatus=%s, originalDurationMs=%d",
                workflowId, originalStatus, originalDurationMs);

            // 4. Phase 3.8: 调用 WorkflowSchedulerService.replayWorkflow() 触发 Replay
            return workflowScheduler.replayWorkflow(workflowId)
                .onItem().transformToUni(replayWorkflow -> {
                    LOG.infof("Replay 完成: workflowId=%s, replayStatus=%s, replayDurationMs=%d",
                        workflowId, replayWorkflow.status, replayWorkflow.durationMs);

                    // 5. 比对结果
                    boolean anomalyReproduced = compareResults(
                        originalStatus, replayWorkflow.status,
                        originalErrorMessage, replayWorkflow.errorMessage,
                        originalDurationMs, replayWorkflow.durationMs
                    );

                    // 6. 构建验证结果
                    VerificationResult result = new VerificationResult(
                        true,
                        anomalyReproduced,
                        workflowId.toString(),
                        Instant.now(),
                        originalDurationMs,
                        replayWorkflow.durationMs
                    );

                    // 7. 记录验证结果到 anomaly_reports（响应式）
                    return anomalyWorkflowService.recordVerificationResult(action.anomalyId, result)
                        .replaceWith(result);
                })
                .onFailure(IllegalStateException.class).recoverWithUni(e -> {
                    LOG.warnf("Replay 验证失败（clock_times 缺失）: %s", e.getMessage());
                    VerificationResult result = new VerificationResult(
                        false,
                        null,
                        workflowId.toString(),
                        Instant.now(),
                        originalDurationMs,
                        null
                    );
                    return anomalyWorkflowService.recordVerificationResult(action.anomalyId, result)
                        .replaceWith(result);
                })
                .onFailure(TimeoutException.class).recoverWithUni(e -> {
                    LOG.errorf("Replay 验证超时: workflowId=%s", workflowId);
                    VerificationResult result = new VerificationResult(
                        false,
                        null,
                        workflowId.toString(),
                        Instant.now(),
                        originalDurationMs,
                        null
                    );
                    return anomalyWorkflowService.recordVerificationResult(action.anomalyId, result)
                        .replaceWith(result);
                })
                .onFailure().invoke(e -> {
                    LOG.errorf(e, "Replay 验证失败: action=%d, workflowId=%s", action.id, workflowId);
                });

        } catch (Exception e) {
            LOG.errorf(e, "Replay 验证准备失败: action=%d", action.id);
            return Uni.createFrom().failure(e);
        }
    }

    /**
     * 执行自动回滚
     *
     * 流程：
     * 1. 从 payload 提取 targetVersion
     * 2. 查询异常报告获取 policyId
     * 3. 调用 PolicyVersionService.rollbackToVersion
     * 4. 发布 AuditEvent.anomalyAutoRollback
     *
     * @param action 异常动作实体
     * @return 回滚是否成功
     */
    @Transactional
    public Uni<Boolean> executeAutoRollback(AnomalyActionEntity action) {
        String idempotencyKey = String.format("ROLLBACK_%s_%s", action.anomalyId, action.id);
        boolean acquired = inboxGuard.tryAcquireBlocking(idempotencyKey, "AUTO_ROLLBACK", resolveInboxTenant(action));
        if (!acquired) {
            LOG.infof("检测到重复自动回滚请求，跳过执行：%s", idempotencyKey);
            return Uni.createFrom().item(Boolean.TRUE);
        }
        AnomalyActionPayload payload = action.deserializePayload();
        return performAutoRollback(action, payload);
    }

    /**
     * 由于动作实体尚未持久化租户信息，这里使用异常 ID 派生命名空间，
     * 保证 InboxGuard 在重复动作去重时具备最小隔离度。
     */
    private String resolveInboxTenant(AnomalyActionEntity action) {
        if (action == null) {
            return null;
        }
        if (action.tenantId != null && !action.tenantId.isBlank()) {
            return action.tenantId;
        }
        if (action.anomalyId == null) {
            return null;
        }
        return "ANOMALY-" + action.anomalyId;
    }

    private Uni<Boolean> performAutoRollback(AnomalyActionEntity action, AnomalyActionPayload payload) {
        // Phase 3.8 Task 3: 记录回滚尝试次数
        anomalyMetrics.recordRollbackAttempt();

        // Phase 3.8 Task 3: 开始计时
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            AnomalyReportEntity anomaly = AnomalyReportEntity.findById(action.anomalyId);
            if (anomaly == null) {
                anomalyMetrics.recordRollbackFailed();
                return Uni.createFrom().failure(
                    new IllegalArgumentException("异常报告不存在: anomalyId=" + action.anomalyId)
                );
            }

            Long targetVersion = payload.targetVersion();
            if (targetVersion == null) {
                anomalyMetrics.recordRollbackFailed();
                return Uni.createFrom().failure(
                    new IllegalArgumentException("Payload 缺少 targetVersion")
                );
            }

            PolicyVersion currentVersion = policyVersionService.getActiveVersion(anomaly.policyId);
            Long fromVersion = currentVersion != null ? currentVersion.version : null;

            // Phase 3.8 Task 3: 版本验证 - 检测版本漂移
            if (fromVersion != null && fromVersion.equals(targetVersion)) {
                LOG.warnf("版本幂等检测: 当前版本 %d 与目标版本相同，跳过回滚", targetVersion);
                // 幂等操作视为成功
                anomalyMetrics.recordRollbackSuccess();
                sample.stop(anomalyMetrics.getRollbackExecutionTimer());
                return Uni.createFrom().item(true);
            }

            // Phase 3.8 Task 3: 版本验证 - 检测版本漂移（版本号不匹配预期）
            PolicyVersion targetPolicyVersion = PolicyVersion.findByVersion(anomaly.policyId, targetVersion);
            if (targetPolicyVersion == null) {
                LOG.errorf("版本漂移检测: 目标版本 %d 不存在（可能已被删除）", targetVersion);
                anomalyMetrics.recordRollbackFailed();
                return Uni.createFrom().failure(
                    new IllegalStateException("目标版本不存在: version=" + targetVersion)
                );
            }

            policyVersionService.rollbackToVersion(anomaly.policyId, targetVersion);

            LOG.infof("异常 %d 触发自动回滚: %s from %d to %d",
                action.anomalyId, anomaly.policyId, fromVersion, targetVersion);

            // Phase 3.8 Task 3: 记录成功并停止计时
            anomalyMetrics.recordRollbackSuccess();
            sample.stop(anomalyMetrics.getRollbackExecutionTimer());

            return Uni.createFrom().item(true);

        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "自动回滚失败: action=%d", action.id);
            anomalyMetrics.recordRollbackFailed();
            sample.stop(anomalyMetrics.getRollbackExecutionTimer());
            return Uni.createFrom().failure(e);
        } catch (Exception e) {
            LOG.errorf(e, "自动回滚执行异常: action=%d", action.id);
            anomalyMetrics.recordRollbackFailed();
            sample.stop(anomalyMetrics.getRollbackExecutionTimer());
            return Uni.createFrom().failure(e);
        }
    }

    /**
     * 比对原始执行与 Replay 结果（Phase 3.8 增强）
     *
     * 判断异常是否在 Replay 中重现：
     * - 如果原始和 Replay 都失败（status=FAILED）→ 异常重现
     * - 如果原始失败但 Replay 成功 → 异常未重现（可能是瞬态问题）
     * - 其他情况 → 异常未重现
     *
     * Phase 3.8 增强：
     * - 比对错误消息（如果都失败，记录错误消息是否一致）
     * - 比对持续时间（允许 10% 误差）
     *
     * @param originalStatus       原始执行状态
     * @param replayStatus         Replay 执行状态
     * @param originalErrorMessage 原始错误消息
     * @param replayErrorMessage   Replay 错误消息
     * @param originalDurationMs   原始持续时间
     * @param replayDurationMs     Replay 持续时间
     * @return true 表示异常重现
     */
    private boolean compareResults(String originalStatus, String replayStatus,
                                    String originalErrorMessage, String replayErrorMessage,
                                    Long originalDurationMs, Long replayDurationMs) {
        // 1. 状态比对（主要判断标准）
        if ("FAILED".equals(originalStatus) && "FAILED".equals(replayStatus)) {
            // 2. 错误消息比对（辅助判断）
            if (originalErrorMessage != null && replayErrorMessage != null) {
                if (!originalErrorMessage.equals(replayErrorMessage)) {
                    LOG.warnf("异常重现但错误消息不同 - 原始: %s, Replay: %s",
                        originalErrorMessage, replayErrorMessage);
                }
            }

            // 3. 持续时间比对（允许 10% 误差）
            if (originalDurationMs != null && replayDurationMs != null) {
                long diff = Math.abs(replayDurationMs - originalDurationMs);
                double diffPercent = (double) diff / originalDurationMs * 100;
                if (diffPercent > 10) {
                    LOG.warnf("持续时间差异较大 - 原始: %dms, Replay: %dms, 差异: %.1f%%",
                        originalDurationMs, replayDurationMs, diffPercent);
                }
            }

            return true;  // 异常重现
        }

        return false;  // 异常未重现或无法判断
    }

}
