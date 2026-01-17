package io.aster.audit.service;

import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.outbox.OutboxStatus;
import io.aster.audit.rest.model.VerificationResult;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.workflow.WorkflowStateEntity;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;

/**
 * 异常工作流服务（Phase 3.7）
 *
 * 采用 orchestration 模式负责异常状态机编排：
 * - 仅负责状态迁移和事件发布
 * - 不实现具体业务逻辑（Replay/Rollback 由 AnomalyActionExecutor 调用现有服务）
 * - 通过 Event<AuditEvent> 集成到现有审计体系
 */
@ApplicationScoped
public class AnomalyWorkflowService {

    @Inject
    Event<AuditEvent> auditEvent;

    /**
     * 提交验证动作到队列
     *
     * 状态迁移：PENDING → VERIFYING
     * 创建 anomaly_actions 记录（actionType=VERIFY_REPLAY, status=PENDING）
     *
     * @param anomalyId 异常报告 ID
     * @return 创建的动作 ID
     */
    @Transactional
    public Uni<Long> submitVerificationAction(Long anomalyId) {
        // 查询异常实体
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(anomalyId);
        if (anomaly == null) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("异常报告不存在: anomalyId=" + anomalyId)
            );
        }

        // Phase 3.8: 检查 sampleWorkflowId 是否存在
        if (anomaly.sampleWorkflowId == null) {
            Log.warnf("异常报告 %d 缺少 sampleWorkflowId，跳过验证动作创建", anomalyId);
            return Uni.createFrom().nullItem();  // 跳过创建，返回 null
        }

        // Phase 3.8: 检查 workflow 的 clockTimes 是否存在
        WorkflowStateEntity workflow = WorkflowStateEntity.findByWorkflowId(anomaly.sampleWorkflowId).orElse(null);
        if (workflow == null || workflow.clockTimes == null || workflow.clockTimes.isBlank()) {
            Log.warnf("Workflow %s 缺少 clockTimes，跳过验证动作创建", anomaly.sampleWorkflowId);
            return Uni.createFrom().nullItem();  // 跳过创建，返回 null
        }

        // 更新异常状态为 VERIFYING
        anomaly.status = "VERIFYING";
        anomaly.persist();

        // Phase 3.8: 构建 payload（从 sampleWorkflowId 提取）
        String payload = Json.createObjectBuilder()
            .add("workflowId", anomaly.sampleWorkflowId.toString())
            .build()
            .toString();
        Log.infof("提交验证动作: anomalyId=%d, workflowId=%s", anomalyId, anomaly.sampleWorkflowId);

        // 创建验证动作
        AnomalyActionEntity action = new AnomalyActionEntity();
        action.anomalyId = anomalyId;
        action.actionType = "VERIFY_REPLAY";
        action.status = OutboxStatus.PENDING;
        action.payload = payload;  // Phase 3.8: 填充 payload
        action.tenantId = resolveOutboxTenant(anomalyId);
        action.createdAt = Instant.now();
        action.persist();

        // 发布审计事件
        auditEvent.fireAsync(AuditEvent.anomalyVerification(
            anomaly.policyId,
            anomalyId,
            "VERIFY_REPLAY"
        ));

        return Uni.createFrom().item(action.id);
    }

    private String resolveOutboxTenant(Long anomalyId) {
        if (anomalyId == null) {
            return null;
        }
        return "ANOMALY-" + anomalyId;
    }

    /**
     * 更新异常状态
     *
     * 支持的状态转换：
     * - VERIFYING → VERIFIED
     * - VERIFIED → RESOLVED
     * - VERIFIED → DISMISSED
     * - PENDING → DISMISSED
     *
     * @param anomalyId 异常报告 ID
     * @param newStatus 新状态
     * @param notes     处置备注
     * @return 成功标志
     */
    @Transactional
    public Uni<Boolean> updateStatus(Long anomalyId, String newStatus, String notes) {
        // 查询异常实体
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(anomalyId);
        if (anomaly == null) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("异常报告不存在: anomalyId=" + anomalyId)
            );
        }

        String oldStatus = anomaly.status;

        // 更新状态
        anomaly.status = newStatus;
        if (notes != null && !notes.isBlank()) {
            anomaly.resolutionNotes = notes;
        }

        // 如果状态为 RESOLVED 或 DISMISSED，记录解决时间
        if ("RESOLVED".equals(newStatus) || "DISMISSED".equals(newStatus)) {
            anomaly.resolvedAt = Instant.now();
        }

        anomaly.persist();

        // 发布审计事件
        auditEvent.fireAsync(AuditEvent.anomalyStatusChange(
            anomaly.policyId,
            anomalyId,
            oldStatus,
            newStatus,
            notes
        ));

        return Uni.createFrom().item(true);
    }

    /**
     * 记录验证结果
     *
     * 将 VerificationResult 序列化为 JSONB 并写入 verification_result 字段
     * Phase 3.8 Task 2: 如果异常可复现（anomalyReproduced=true），触发自动回滚
     *
     * @param anomalyId          异常报告 ID
     * @param verificationResult 验证结果对象
     * @return 成功标志
     */
    @Transactional
    public Uni<Boolean> recordVerificationResult(Long anomalyId, VerificationResult verificationResult) {
        // 查询异常实体
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(anomalyId);
        if (anomaly == null) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("异常报告不存在: anomalyId=" + anomalyId)
            );
        }

        // 序列化验证结果为 JSON 字符串
        String jsonResult = verificationResult.toJson().toString();
        anomaly.verificationResult = jsonResult;

        // 状态迁移：VERIFYING → VERIFIED
        anomaly.status = "VERIFIED";
        anomaly.persist();

        // Phase 3.8 Task 2: 如果异常可复现，触发自动回滚
        if (verificationResult.anomalyReproduced()) {
            return submitAutoRollbackAction(anomaly)
                .onItem().transform(actionId -> true);
        }

        return Uni.createFrom().item(true);
    }

    /**
     * Phase 3.8 Task 2: 提交自动回滚动作到队列
     *
     * 查找目标版本 → 构建 payload → 创建 AUTO_ROLLBACK 动作 → 发布审计事件
     *
     * @param anomaly 异常报告实体
     * @return 创建的动作 ID，如果无历史版本则返回 null
     */
    private Uni<Long> submitAutoRollbackAction(AnomalyReportEntity anomaly) {
        try {
            // Step 1: 获取当前版本号
            PolicyVersion currentVersion = PolicyVersion.findById(anomaly.versionId);
            if (currentVersion == null) {
                Log.warnf("异常 %d 的版本不存在: versionId=%d", anomaly.id, anomaly.versionId);
                return Uni.createFrom().nullItem();
            }

            // Step 2: 查找目标版本
            Long targetVersion = findPreviousVersion(anomaly.policyId, currentVersion.version);
            if (targetVersion == null) {
                Log.warnf("异常 %d 无历史版本可回滚，跳过 AUTO_ROLLBACK", anomaly.id);
                return Uni.createFrom().nullItem();
            }

            // Step 3: 构建 payload
            String payload = Json.createObjectBuilder()
                .add("targetVersion", targetVersion)
                .build()
                .toString();

            // Step 4: 创建动作
            AnomalyActionEntity action = new AnomalyActionEntity();
            action.anomalyId = anomaly.id;
            action.actionType = "AUTO_ROLLBACK";
            action.status = OutboxStatus.PENDING;
            action.payload = payload;
            action.tenantId = resolveOutboxTenant(anomaly.id);
            action.createdAt = Instant.now();
            action.persist();

            // Step 5: 发布审计事件
            auditEvent.fireAsync(AuditEvent.anomalyAutoRollback(
                anomaly.policyId,
                anomaly.id,
                currentVersion.version,
                targetVersion
            ));

            Log.infof("异常 %d 创建 AUTO_ROLLBACK 动作：从版本 %d 回滚到 %d",
                anomaly.id, currentVersion.version, targetVersion);

            return Uni.createFrom().item(action.id);
        } catch (Exception e) {
            Log.errorf(e, "创建 AUTO_ROLLBACK 动作失败: anomalyId=%d", anomaly.id);
            return Uni.createFrom().nullItem();
        }
    }

    /**
     * Phase 3.8 Task 2: 查找上一个版本
     *
     * 查询指定策略的上一个版本（version < currentVersion）
     *
     * @param policyId       策略 ID
     * @param currentVersion 当前版本号
     * @return 上一个版本号，如果不存在则返回 null
     */
    private Long findPreviousVersion(String policyId, Long currentVersion) {
        if (policyId == null || currentVersion == null) {
            return null;
        }

        // 查询所有版本，按 version 降序（PolicyVersion.findAllVersions 已排序）
        List<PolicyVersion> versions = PolicyVersion.findAllVersions(policyId);

        // 找到第一个小于 currentVersion 的版本
        return versions.stream()
            .filter(v -> v.version < currentVersion)
            .map(v -> v.version)
            .findFirst()
            .orElse(null);
    }
}
