package io.aster.workflow;

import aster.runtime.workflow.WorkflowEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Saga 补偿执行器
 *
 * 基于 Saga 模式实现补偿协调：
 * - 在 step 失败时触发补偿链
 * - 按照逆序（LIFO）执行补偿
 * - 记录补偿事件到事件流
 * - 补偿失败时标记需要人工介入
 */
@ApplicationScoped
public class SagaCompensationExecutor {

    @Inject
    PostgresEventStore eventStore;

    @Inject
    ObjectMapper objectMapper;

    /**
     * 处理 step 失败并触发补偿
     *
     * 从事件流中识别所有已完成的 step，按逆序调度补偿。
     *
     * @param workflowId workflow 唯一标识符
     * @param failedStepId 失败的 step ID
     * @param error 失败原因
     */
    public void handleStepFailure(String workflowId, String failedStepId, Throwable error) {
        Log.infof("Handling step failure for workflow %s, failed step: %s", workflowId, failedStepId);

        try {
            // 获取事件历史
            List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);

            // 识别已完成的 step（按序列号）
            List<CompletedStep> completedSteps = extractCompletedSteps(events);

            // 过滤出失败 step 之前完成的 step
            int failedStepSeq = findStepSequence(events, failedStepId);
            // 并发补偿遵循 LIFO：先比较完成时间，再用序列号打破平局
            Comparator<CompletedStep> compensationOrder = Comparator
                    .comparing(CompletedStep::getCompletedAt, Comparator.reverseOrder())
                    .thenComparing(CompletedStep::getSequence, Comparator.reverseOrder());

            List<CompletedStep> stepsToCompensate = completedSteps.stream()
                    .filter(s -> s.getSequence() < failedStepSeq)
                    .filter(CompletedStep::hasCompensation)
                    .sorted(compensationOrder)
                    .collect(Collectors.toList());

            Log.infof("Found %d steps to compensate for workflow %s", stepsToCompensate.size(), workflowId);

            // 为每个 step 调度补偿
            for (CompletedStep step : stepsToCompensate) {
                Map<String, Object> compensationPayload = Map.of(
                        "stepId", step.getStepId(),
                        "stepSequence", step.getSequence(),
                        "originalResult", step.getResult() != null ? step.getResult() : "",
                        "reason", "Step " + failedStepId + " failed: " + error.getMessage()
                );

                eventStore.appendEvent(
                        workflowId,
                        WorkflowEvent.Type.COMPENSATION_SCHEDULED,
                        compensationPayload
                );

                Log.debugf("Scheduled compensation for step %s in workflow %s", step.getStepId(), workflowId);
            }

            // 记录 workflow 进入补偿状态
            if (!stepsToCompensate.isEmpty()) {
                Map<String, Object> stateChangePayload = Map.of(
                        "previousStatus", "RUNNING",
                        "newStatus", "COMPENSATING",
                        "trigger", "StepFailed: " + failedStepId
                );
                eventStore.appendEvent(workflowId, "WorkflowStatusChanged", stateChangePayload);
            }

        } catch (Exception e) {
            Log.errorf(e, "Failed to handle step failure for workflow %s", workflowId);
            throw new RuntimeException("Failed to schedule compensation", e);
        }
    }

    /**
     * 执行单个补偿
     *
     * 注意：实际的补偿逻辑执行需要 Truffle 运行时支持。
     * 这里仅记录补偿事件，实际执行由 WorkflowNode 完成。
     *
     * @param workflowId workflow 唯一标识符
     * @param stepId step 唯一标识符
     * @param originalResult step 原始执行结果
     */
    public void executeCompensation(String workflowId, String stepId, Object originalResult) {
        Log.infof("Executing compensation for step %s in workflow %s", stepId, workflowId);

        try {
            // 记录补偿开始
            Map<String, Object> startPayload = Map.of(
                    "stepId", stepId,
                    "originalResult", originalResult != null ? originalResult : ""
            );
            eventStore.appendEvent(workflowId, WorkflowEvent.Type.COMPENSATION_STARTED, startPayload);

            // 实际补偿逻辑由 Truffle WorkflowNode 执行
            // 这里假设补偿成功（简化实现）

            // 记录补偿完成
            Map<String, Object> completePayload = Map.of(
                    "stepId", stepId,
                    "compensationResult", "success"
            );
            eventStore.appendEvent(workflowId, WorkflowEvent.Type.COMPENSATION_COMPLETED, completePayload);

            Log.infof("Compensation completed for step %s in workflow %s", stepId, workflowId);

        } catch (Exception e) {
            Log.errorf(e, "Compensation failed for step %s in workflow %s", stepId, workflowId);

            // 记录补偿失败
            Map<String, Object> failurePayload = Map.of(
                    "stepId", stepId,
                    "error", e.getMessage(),
                    "requiresManualIntervention", true
            );
            eventStore.appendEvent(workflowId, WorkflowEvent.Type.COMPENSATION_FAILED, failurePayload);

            throw new CompensationException("Compensation failed for step " + stepId, e);
        }
    }

    /**
     * 检查所有补偿是否完成
     *
     * @param events 事件列表
     * @return true 如果所有调度的补偿都已完成
     */
    public boolean areAllCompensationsCompleted(List<WorkflowEvent> events) {
        long scheduledCount = events.stream()
                .filter(e -> WorkflowEvent.Type.COMPENSATION_SCHEDULED.equals(e.getEventType()))
                .count();

        long completedCount = events.stream()
                .filter(e -> WorkflowEvent.Type.COMPENSATION_COMPLETED.equals(e.getEventType()))
                .count();

        return scheduledCount > 0 && scheduledCount == completedCount;
    }

    /**
     * 检查是否有补偿失败
     *
     * @param events 事件列表
     * @return true 如果有任何补偿失败
     */
    public boolean hasCompensationFailure(List<WorkflowEvent> events) {
        return events.stream()
                .anyMatch(e -> WorkflowEvent.Type.COMPENSATION_FAILED.equals(e.getEventType()));
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从事件流中提取已完成的 step
     */
    private List<CompletedStep> extractCompletedSteps(List<WorkflowEvent> events) {
        Map<String, StepMetadata> startedSteps = new HashMap<>();
        List<CompletedStep> completedSteps = new ArrayList<>();
        int sequence = 0;

        for (WorkflowEvent event : events) {
            if (WorkflowEvent.Type.STEP_STARTED.equals(event.getEventType())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) event.getPayload();
                String stepId = (String) payload.get("stepId");
                if (stepId == null) {
                    continue;
                }
                List<String> dependencies = extractDependencies(payload.get("dependencies"));
                startedSteps.put(stepId, new StepMetadata(sequence++, dependencies));
            } else if (WorkflowEvent.Type.STEP_COMPLETED.equals(event.getEventType())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) event.getPayload();
                String stepId = (String) payload.get("stepId");
                if (stepId == null) {
                    continue;
                }

                StepMetadata metadata = startedSteps.get(stepId);
                int resolvedSequence = metadata != null ? metadata.sequence() : sequence++;
                List<String> dependencies = extractDependencies(payload.get("dependencies"));
                if (dependencies.isEmpty() && metadata != null) {
                    dependencies = metadata.dependencies();
                }
                Instant completedAt = parseCompletedAt(payload.get("completedAt"), event.getOccurredAt());
                Object result = payload.get("result");
                boolean hasCompensation = Boolean.TRUE.equals(payload.get("hasCompensation"));

                completedSteps.add(new CompletedStep(
                        stepId,
                        dependencies,
                        completedAt,
                        resolvedSequence,
                        result,
                        hasCompensation
                ));
            }
        }

        return completedSteps;
    }

    /**
     * 查找 step 的序列号
     */
    private int findStepSequence(List<WorkflowEvent> events, String stepId) {
        int sequence = 0;
        for (WorkflowEvent event : events) {
            if ("StepStarted".equals(event.getEventType())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) event.getPayload();
                String currentStepId = (String) payload.get("stepId");
                if (stepId.equals(currentStepId)) {
                    return sequence;
                }
                sequence++;
            }
        }
        return Integer.MAX_VALUE; // 未找到
    }

    /**
     * 已完成的 step 数据类
     */
    private static class CompletedStep {
        final String stepId;
        final List<String> dependencies;
        final Instant completedAt;
        final int sequence;
        final Object result;
        final boolean hasCompensation;

        CompletedStep(String stepId,
                      List<String> dependencies,
                      Instant completedAt,
                      int sequence,
                      Object result,
                      boolean hasCompensation) {
            this.stepId = stepId;
            this.dependencies = dependencies == null ? Collections.emptyList() : List.copyOf(dependencies);
            this.completedAt = completedAt;
            this.sequence = sequence;
            this.result = result;
            this.hasCompensation = hasCompensation;
        }

        String getStepId() {
            return stepId;
        }

        List<String> getDependencies() {
            return dependencies;
        }

        Instant getCompletedAt() {
            return completedAt;
        }

        int getSequence() {
            return sequence;
        }

        Object getResult() {
            return result;
        }

        boolean hasCompensation() {
            return hasCompensation;
        }
    }

    private record StepMetadata(int sequence, List<String> dependencies) {
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractDependencies(Object rawDependencies) {
        if (rawDependencies instanceof List<?> rawList) {
            List<String> resolved = rawList.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(Collectors.toList());
            return resolved.isEmpty() ? Collections.emptyList() : List.copyOf(resolved);
        }
        return Collections.emptyList();
    }

    private static Instant parseCompletedAt(Object rawCompletedAt, Instant fallback) {
        if (rawCompletedAt instanceof String completedAtStr && !completedAtStr.isEmpty()) {
            try {
                return Instant.parse(completedAtStr);
            } catch (Exception ignored) {
                // 回退至事件时间，兼容旧事件
            }
        }
        return fallback != null ? fallback : Instant.EPOCH;
    }

    /**
     * 补偿异常
     */
    public static class CompensationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
