package io.aster.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * CQRS 查询投影构建器
 *
 * 职责：
 * - 监听 workflow 事件流
 * - 更新 WorkflowQueryViewEntity（Read Model）预计算统计
 * - 维护事件计数、步骤计数和性能指标
 *
 * 设计原则：
 * - 最终一致性：投影更新失败不影响事件追加
 * - 幂等性：重复调用不会导致错误状态
 * - 性能优化：预计算避免查询时实时聚合
 */
@ApplicationScoped
public class WorkflowQueryProjectionBuilder {

    @Inject
    ObjectMapper objectMapper;

    /**
     * 基于事件更新查询视图
     *
     * 此方法在 PostgresEventStore.appendEvent() 后调用，
     * 用于更新 CQRS Read Model。
     *
     * @param workflowId workflow 唯一标识符
     * @param eventType  事件类型
     * @param eventSeq   事件序列号（per-workflow）
     * @param payload    事件负载数据
     * @param state      当前 workflow 状态实体
     */
    @Transactional
    public void updateProjection(String workflowId,
                                 String eventType,
                                 long eventSeq,
                                 String payload,
                                 WorkflowStateEntity state) {
        try {
            UUID wfId = UUID.fromString(workflowId);

            // 获取或创建查询视图
            WorkflowQueryViewEntity view = WorkflowQueryViewEntity.getOrCreate(wfId);

            // 更新基本信息
            view.status = state.status;
            view.policyVersionId = state.policyVersionId;
            view.updatedAt = Instant.now();
            view.totalEvents = eventSeq;  // Per-workflow 序列号即事件总数

            // 根据事件类型更新视图
            switch (eventType) {
                case "WorkflowStarted" -> handleWorkflowStarted(view, payload, state);
                case "StepStarted" -> handleStepStarted(view, payload);
                case "StepCompleted" -> handleStepCompleted(view, payload);
                case "StepFailed" -> handleStepFailed(view, payload);
                case "WorkflowCompleted" -> handleWorkflowCompleted(view, payload, state);
                case "WorkflowFailed" -> handleWorkflowFailed(view, payload, state);
                case "CompensationScheduled", "CompensationCompleted", "CompensationFailed" ->
                        handleCompensationEvent(view, eventType);
                case "WorkflowTerminated" -> handleWorkflowTerminated(view, state);
                default -> {
                    // 其他事件类型不影响统计，仅更新事件计数
                    Log.debugf("Event type %s does not affect query projection for workflow %s",
                            eventType, workflowId);
                }
            }

            // 更新快照信息（引用）
            if (state.snapshotSeq != null && state.snapshotSeq > 0) {
                view.snapshotSeq = state.snapshotSeq;
                view.hasSnapshot = true;
            }

            // 持久化更新
            view.persist();

            Log.debugf("Updated query projection for workflow %s, event %s (seq=%d)",
                    workflowId, eventType, eventSeq);

        } catch (Exception e) {
            // 投影更新失败不影响事件追加（最终一致性）
            Log.warnf(e, "Failed to update query projection for workflow %s, event %s",
                    workflowId, eventType);
        }
    }

    /**
     * 处理 WorkflowStarted 事件
     */
    private void handleWorkflowStarted(WorkflowQueryViewEntity view,
                                       String payload,
                                       WorkflowStateEntity state) {
        view.createdAt = state.createdAt;
        view.startedAt = state.startedAt != null ? state.startedAt : Instant.now();

        // 解析 payload 中的 policyVersionId（如果有）
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            if (data.containsKey("policyVersionId")) {
                view.policyVersionId = ((Number) data.get("policyVersionId")).longValue();
            }
        } catch (Exception e) {
            Log.debugf("No policyVersionId in WorkflowStarted payload for workflow %s",
                    view.workflowId);
        }
    }

    /**
     * 处理 StepStarted 事件
     */
    private void handleStepStarted(WorkflowQueryViewEntity view, String payload) {
        // 增加步骤总数
        view.totalSteps++;
    }

    /**
     * 处理 StepCompleted 事件
     */
    private void handleStepCompleted(WorkflowQueryViewEntity view, String payload) {
        // 增加已完成步骤数
        view.completedSteps++;

        // 尝试解析步骤执行时长（如果 payload 中有 duration）
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);

            // 计算平均步骤时长（简化：基于已完成步骤数）
            if (data.containsKey("duration") && view.completedSteps > 0) {
                long stepDuration = ((Number) data.get("duration")).longValue();
                long currentTotal = (view.avgStepDurationMs != null ? view.avgStepDurationMs : 0L)
                        * (view.completedSteps - 1);
                view.avgStepDurationMs = (currentTotal + stepDuration) / view.completedSteps;
            }
        } catch (Exception e) {
            Log.debugf("No duration info in StepCompleted payload");
        }
    }

    /**
     * 处理 StepFailed 事件
     */
    private void handleStepFailed(WorkflowQueryViewEntity view, String payload) {
        // 增加失败步骤数
        view.failedSteps++;

        // 提取错误信息（如果有）
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            if (data.containsKey("error")) {
                view.errorMessage = data.get("error").toString();
            }
        } catch (Exception e) {
            Log.debugf("No error info in StepFailed payload");
        }
    }

    /**
     * 处理 WorkflowCompleted 事件
     */
    private void handleWorkflowCompleted(WorkflowQueryViewEntity view,
                                        String payload,
                                        WorkflowStateEntity state) {
        view.completedAt = state.completedAt != null ? state.completedAt : Instant.now();

        // 计算总执行时长
        if (view.startedAt != null && view.completedAt != null) {
            view.durationMs = Duration.between(view.startedAt, view.completedAt).toMillis();
        }

        // 保存执行结果
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            if (data.containsKey("result")) {
                view.result = objectMapper.writeValueAsString(data.get("result"));
            }
        } catch (Exception e) {
            Log.debugf("No result in WorkflowCompleted payload");
        }
    }

    /**
     * 处理 WorkflowFailed 事件
     */
    private void handleWorkflowFailed(WorkflowQueryViewEntity view,
                                     String payload,
                                     WorkflowStateEntity state) {
        view.completedAt = state.completedAt != null ? state.completedAt : Instant.now();

        // 计算总执行时长
        if (view.startedAt != null && view.completedAt != null) {
            view.durationMs = Duration.between(view.startedAt, view.completedAt).toMillis();
        }

        // 提取错误信息
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            if (data.containsKey("error")) {
                view.errorMessage = data.get("error").toString();
            }
        } catch (Exception e) {
            view.errorMessage = state.errorMessage != null ? state.errorMessage : "Unknown error";
        }
    }

    /**
     * 处理 Compensation 事件
     */
    private void handleCompensationEvent(WorkflowQueryViewEntity view, String eventType) {
        // Compensation 事件不直接影响统计，状态已通过 state.status 同步
        Log.debugf("Handled compensation event %s for workflow %s", eventType, view.workflowId);
    }

    /**
     * 处理 WorkflowTerminated 事件
     */
    private void handleWorkflowTerminated(WorkflowQueryViewEntity view, WorkflowStateEntity state) {
        view.completedAt = Instant.now();

        // 计算总执行时长
        if (view.startedAt != null && view.completedAt != null) {
            view.durationMs = Duration.between(view.startedAt, view.completedAt).toMillis();
        }
    }
}
