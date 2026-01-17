package io.aster.audit.entity;

import io.aster.audit.outbox.GenericOutboxEntity;
import io.aster.audit.outbox.OutboxStatus;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Page;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.StringReader;
import java.util.List;
import java.util.UUID;

/**
 * 异常响应动作实体（Phase 3.7）
 *
 * 采用 Outbox 模式解耦异常检测与响应执行。
 * 检测任务（AnomalyDetectionScheduler）只负责写入动作队列，
 * 独立的消费器（AnomalyActionScheduler）异步处理动作。
 */
@RegisterForReflection
@Entity
@Table(name = "anomaly_actions")
public class AnomalyActionEntity extends GenericOutboxEntity<AnomalyActionPayload> {

    /**
     * 关联的异常报告 ID
     */
    @Column(name = "anomaly_id", nullable = false)
    public Long anomalyId;

    /**
     * 动作类型：VERIFY_REPLAY, AUTO_ROLLBACK
     */
    @Column(name = "action_type", nullable = false, length = 32)
    public String actionType;

    // ==================== Panache Active Record 查询方法 ====================

    /**
     * 查询待处理的动作（PENDING 状态），按创建时间升序排列
     *
     * @param limit 最大返回数量
     * @return 待处理动作列表
     */
    public static List<AnomalyActionEntity> findPendingActions(int limit) {
        return find("status = ?1 ORDER BY createdAt ASC", OutboxStatus.PENDING)
            .page(Page.ofSize(limit))
            .list();
    }

    /**
     * 查询指定异常的所有动作历史
     *
     * @param anomalyId 异常报告 ID
     * @return 动作列表，按创建时间降序排列
     */
    public static List<AnomalyActionEntity> findByAnomalyId(Long anomalyId) {
        return find("anomalyId = ?1 ORDER BY createdAt DESC", anomalyId).list();
    }

    /**
     * 查询正在执行的动作（RUNNING 状态）
     *
     * @return 正在执行的动作列表
     */
    public static List<AnomalyActionEntity> findRunningActions() {
        return find("status = ?1 ORDER BY startedAt ASC", OutboxStatus.RUNNING).list();
    }

    /**
     * 统计待处理动作数量
     *
     * @return 待处理动作数
     */
    public static long countPending() {
        return count("status = ?1", OutboxStatus.PENDING);
    }

    @Override
    public String getEventType() {
        return actionType;
    }

    @Override
    public AnomalyActionPayload deserializePayload() {
        if (payload == null || payload.isBlank()) {
            return new AnomalyActionPayload(null, null);
        }
        try (JsonReader reader = Json.createReader(new StringReader(payload))) {
            JsonObject json = reader.readObject();
            UUID workflowId = json.containsKey("workflowId") && !json.isNull("workflowId")
                ? UUID.fromString(json.getString("workflowId"))
                : null;
            Long targetVersion = json.containsKey("targetVersion") && !json.isNull("targetVersion")
                ? json.getJsonNumber("targetVersion").longValue()
                : null;
            return new AnomalyActionPayload(workflowId, targetVersion);
        } catch (Exception e) {
            Log.warnf("解析异常动作 payload 失败: action=%s, error=%s", id, e.getMessage());
            return new AnomalyActionPayload(null, null);
        }
    }
}
