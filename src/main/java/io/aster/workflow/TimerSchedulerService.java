package io.aster.workflow;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Timer 调度服务
 *
 * 负责轮询到期的 timers 并触发 workflow 恢复执行。
 * 支持一次性 timer 和周期性 timer。
 */
@ApplicationScoped
public class TimerSchedulerService {

    @Inject
    WorkflowSchedulerService workflowScheduler;

    /**
     * 每秒轮询到期的 timers
     *
     * 使用乐观锁避免重复执行（通过更新 status 字段）
     */
    @Scheduled(every = "1s")
    @Transactional
    public void pollExpiredTimers() {
        try {
            List<WorkflowTimerEntity> expired = WorkflowTimerEntity.find(
                "fireAt <= ?1 AND status = 'PENDING' ORDER BY fireAt",
                Instant.now()
            ).page(0, 100).list(); // 每次最多处理 100 个 timer

            for (WorkflowTimerEntity timer : expired) {
                processExpiredTimer(timer);
            }

            if (!expired.isEmpty()) {
                Log.debugf("Processed %d expired timers", expired.size());
            }

        } catch (Exception e) {
            Log.errorf(e, "Error polling expired timers");
        }
    }

    /**
     * 处理单个到期的 timer
     *
     * @param timer 到期的 timer 实体
     */
    private void processExpiredTimer(WorkflowTimerEntity timer) {
        try {
            // 乐观锁：先更新状态避免重复执行
            timer.status = "EXECUTING";
            timer.persist();

            // 触发 workflow step 继续执行
            if (timer.stepId != null) {
                workflowScheduler.resumeWorkflowStep(timer.workflowId.toString(), timer.stepId);
            } else {
                // 如果没有指定 stepId，触发整个 workflow 恢复
                workflowScheduler.resumeWorkflow(timer.workflowId.toString());
            }

            // 处理周期性 timer
            if (timer.intervalMillis != null && timer.intervalMillis > 0) {
                // 计算下次执行时间（避免时钟漂移）
                timer.fireAt = Instant.now().plusMillis(timer.intervalMillis);
                timer.status = "PENDING";
                timer.retryCount = 0; // 重置重试计数
                timer.persist();

                Log.debugf("Rescheduled periodic timer %s for workflow %s (next fire: %s)",
                    timer.timerId, timer.workflowId, timer.fireAt);
            } else {
                // 一次性 timer：标记为已完成
                timer.status = "COMPLETED";
                timer.persist();

                Log.debugf("Completed one-time timer %s for workflow %s",
                    timer.timerId, timer.workflowId);
            }

        } catch (Exception e) {
            // 执行失败：标记为 FAILED 并递增重试计数
            timer.status = "FAILED";
            timer.retryCount++;
            timer.persist();

            Log.errorf(e, "Failed to execute timer %s for workflow %s (retry count: %d)",
                timer.timerId, timer.workflowId, timer.retryCount);

            // 可选：实现指数退避重试策略
            // if (timer.retryCount < maxRetries) {
            //     timer.fireAt = Instant.now().plus(Duration.ofSeconds(1L << timer.retryCount));
            //     timer.status = "PENDING";
            //     timer.persist();
            // }
        }
    }

    /**
     * 创建一次性 timer
     *
     * @param workflowId workflow 唯一标识符
     * @param stepId     step 标识符（可选）
     * @param delay      延迟时间
     * @param payload    timer 负载数据
     * @return 创建的 timer 实体
     */
    @Transactional
    public WorkflowTimerEntity scheduleTimer(
        String workflowId,
        String stepId,
        Duration delay,
        String payload
    ) {
        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = java.util.UUID.randomUUID();
        timer.workflowId = java.util.UUID.fromString(workflowId);
        timer.stepId = stepId;
        timer.fireAt = Instant.now().plus(delay);
        timer.payload = payload;
        timer.status = "PENDING";
        timer.persist();

        Log.debugf("Scheduled timer %s for workflow %s (fire at: %s)",
            timer.timerId, workflowId, timer.fireAt);

        return timer;
    }

    /**
     * 创建周期性 timer
     *
     * @param workflowId workflow 唯一标识符
     * @param stepId     step 标识符（可选）
     * @param interval   执行间隔
     * @param payload    timer 负载数据
     * @return 创建的 timer 实体
     */
    @Transactional
    public WorkflowTimerEntity schedulePeriodicTimer(
        String workflowId,
        String stepId,
        Duration interval,
        String payload
    ) {
        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = java.util.UUID.randomUUID();
        timer.workflowId = java.util.UUID.fromString(workflowId);
        timer.stepId = stepId;
        timer.fireAt = Instant.now().plus(interval);
        timer.intervalMillis = interval.toMillis();
        timer.payload = payload;
        timer.status = "PENDING";
        timer.persist();

        Log.debugf("Scheduled periodic timer %s for workflow %s (interval: %s)",
            timer.timerId, workflowId, interval);

        return timer;
    }

    /**
     * 取消 timer
     *
     * @param timerId timer 唯一标识符
     * @return 是否成功取消
     */
    @Transactional
    public boolean cancelTimer(java.util.UUID timerId) {
        WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
        if (timer != null && "PENDING".equals(timer.status)) {
            timer.status = "CANCELLED";
            timer.persist();
            Log.debugf("Cancelled timer %s", timerId);
            return true;
        }
        return false;
    }
}
