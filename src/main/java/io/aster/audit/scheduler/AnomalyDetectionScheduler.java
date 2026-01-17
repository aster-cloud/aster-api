package io.aster.audit.scheduler;

import io.aster.audit.dto.AnomalyReportDTO;
import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.service.AnomalyWorkflowService;
import io.aster.audit.service.PolicyAnalyticsService;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * 异常检测定时任务（Phase 3.4, Phase 3.7）
 *
 * 每小时执行一次异常检测，将结果持久化到 anomaly_reports 表。
 * 自动清理 30 天前的历史记录，避免数据膨胀。
 *
 * Phase 3.7 扩展：
 * - CRITICAL 异常自动提交验证动作到 anomaly_actions 队列
 * - 异步提交（subscribe().with()），不阻塞检测任务
 * - 提交失败不影响检测任务继续执行
 *
 * 特性：
 * - 并发控制：SKIP 模式避免任务重叠
 * - 事务管理：整个任务原子性，异常时自动回滚
 * - 错误处理：捕获异常后重新抛出，触发回滚并等待下次重试
 * - 配置化：支持环境变量覆盖默认值
 */
@ApplicationScoped
public class AnomalyDetectionScheduler {

    @Inject
    PolicyAnalyticsService analyticsService;

    @Inject
    AnomalyWorkflowService workflowService;

    @ConfigProperty(name = "anomaly.detection.threshold", defaultValue = "0.10")
    double threshold;

    @ConfigProperty(name = "anomaly.detection.days", defaultValue = "30")
    int days;

    @ConfigProperty(name = "anomaly.detection.retention-days", defaultValue = "30")
    int retentionDays;

    /**
     * 定时执行异常检测并持久化
     *
     * cron 表达式：0 0 * * * ?（每小时整点执行）
     * 可通过 application.properties 配置：anomaly.detection.cron
     *
     * concurrentExecution = SKIP：如果前一次任务未完成，跳过本次执行
     */
    @Scheduled(cron = "${anomaly.detection.cron:0 0 * * * ?}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void detectAndPersistAnomalies() {
        Log.infof("开始执行异常检测任务（threshold=%.2f, days=%d）", threshold, days);

        try {
            // 1. 执行异常检测
            List<AnomalyReportDTO> anomalies = analyticsService.detectAnomalies(threshold, days);
            Log.infof("检测到 %d 个异常", anomalies.size());

            // 2. 清理旧数据（在插入新数据前）
            long deletedCount = AnomalyReportEntity.deleteOlderThan(retentionDays);
            if (deletedCount > 0) {
                Log.infof("清理了 %d 条 %d 天前的异常记录", deletedCount, retentionDays);
            }

            // 3. 持久化新检测结果
            for (AnomalyReportDTO dto : anomalies) {
                AnomalyReportEntity entity = new AnomalyReportEntity();
                entity.anomalyType = dto.anomalyType;
                entity.versionId = dto.versionId;
                entity.policyId = dto.policyId;
                entity.metricValue = dto.metricValue;
                entity.threshold = dto.threshold;
                entity.severity = dto.severity;
                entity.description = dto.description;
                entity.recommendation = dto.recommendation;
                entity.detectedAt = dto.detectedAt;
                entity.sampleWorkflowId = dto.sampleWorkflowId;  // Phase 3.8: 写入代表性 workflow ID
                entity.tenantId = dto.tenantId;  // Phase 4.3: 写入租户 ID（从 DTO 获取，确保多租户隔离）
                entity.persist();

                // Phase 3.7: 对 CRITICAL 异常自动提交验证动作
                if ("CRITICAL".equals(entity.severity)) {
                    workflowService.submitVerificationAction(entity.id)
                        .subscribe().with(
                            actionId -> Log.infof("异常 %d 已提交验证动作 %d", entity.id, actionId),
                            failure -> Log.errorf(failure, "异常 %d 提交验证动作失败", entity.id)
                        );
                }
            }

            Log.infof("异常检测任务执行完成，持久化了 %d 条记录", anomalies.size());

        } catch (Exception e) {
            Log.errorf(e, "异常检测任务执行失败");
            // 异常会触发事务回滚，下次调度时重试
            throw e;
        }
    }
}
