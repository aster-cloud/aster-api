package io.aster.workflow;

import aster.runtime.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL 事件存储实现
 *
 * 基于 Panache Active Record 模式实现事件溯源持久化。
 * 支持幂等性追加、事件重放和快照优化。
 */
@ApplicationScoped
public class PostgresEventStore implements EventStore, aster.truffle.runtime.PostgresEventStore {

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowQueryProjectionBuilder projectionBuilder;

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "postgresql")
    String dbKind;

    @ConfigProperty(name = "workflow.snapshot.interval", defaultValue = "100")
    int snapshotInterval;

    /**
     * 快照时间间隔（分钟）
     * P0-2 criterion 4: 基于时间间隔的快照触发
     * 默认 5 分钟
     */
    @ConfigProperty(name = "workflow.snapshot.time-interval-minutes", defaultValue = "5")
    int snapshotTimeIntervalMinutes;

    @ConfigProperty(name = "workflow.snapshot.enabled", defaultValue = "true")
    boolean snapshotEnabled;

    /**
     * 追加事件到 workflow 事件流
     *
     * 实现幂等性保证：
     * - 通过 (workflow_id, idempotency_key) UNIQUE 约束防止重复
     * - 序列号由存储层自动递增
     * - 追加成功后通过 NOTIFY 通知调度器
     *
     * @param workflowId workflow 唯一标识符
     * @param eventType 事件类型
     * @param payload 事件负载数据
     * @return 事件序列号
     */
    @Transactional
    @Override
    public long appendEvent(String workflowId, String eventType, Object payload) {
        return appendEvent(workflowId, eventType, payload, 1, null, null);
    }

    @Transactional
    @Override
    @SuppressWarnings("deprecation") // getOrCreate(UUID) - tenantId not available in EventStore interface
    public long appendEvent(String workflowId, String eventType, Object payload,
                            Integer attemptNumber, Long backoffDelayMs, String failureReason) {
        try {
            UUID wfId = UUID.fromString(workflowId);

            String serializedPayload = serializePayload(payload);
            Integer safeAttempt = attemptNumber != null ? attemptNumber : 1;

            // 生成幂等性键（基于 workflow ID + 事件类型 + payload 哈希）
            String idempotencyKey = generateIdempotencyKey(
                    workflowId,
                    eventType,
                    serializedPayload,
                    safeAttempt,
                    backoffDelayMs,
                    failureReason
            );

            // 检查幂等性：如果事件已存在，返回现有序列号
            Optional<WorkflowEventEntity> existing = WorkflowEventEntity.findByIdempotencyKey(wfId, idempotencyKey);
            if (existing.isPresent()) {
                Log.debugf("Event already exists for workflow %s, idempotency key %s", workflowId, idempotencyKey);
                return existing.get().sequence;
            }

            // 使用 PostgreSQL advisory lock 串行化同一 workflow 的事件追加
            // 直接使用 UUID 的最高64位作为锁ID，确保不同 workflow 可并行执行
            // pg_advisory_xact_lock 在事务结束时自动释放，无需手动解锁
            if (!isH2Database()) {
                // 使用 UUID 的最高64位，提供更好的分布性和更低的碰撞概率
                long lockId = wfId.getMostSignificantBits();
                em.createNativeQuery("SELECT pg_advisory_xact_lock(:lockId)")
                        .setParameter("lockId", lockId)
                        .getSingleResult();
            }

            // 使用数据库 BIGSERIAL 序列生成全局递增序号，保证并发写入无冲突
            long globalSeq = nextSequenceValue();

            // 直接从数据库查询最大序列号，完全绕过 Hibernate 缓存
            // 在 advisory lock 保护下，这保证了读取到最新的已提交值
            Long maxSeq = em.createQuery(
                    "SELECT COALESCE(MAX(e.sequence), 0) FROM WorkflowEventEntity e WHERE e.workflowId = :wfId",
                    Long.class
            ).setParameter("wfId", wfId).getSingleResult();
            long workflowSeq = maxSeq + 1;

            // Phase 3.1: 获取或创建 workflow_state（用于后续状态更新）
            WorkflowStateEntity state = WorkflowStateEntity.getOrCreate(wfId);

            // 创建事件实体
            WorkflowEventEntity event = new WorkflowEventEntity();
            event.workflowId = wfId;
            event.sequence = workflowSeq;  // Per-workflow 序列号
            event.seq = globalSeq;         // 全局序列号
            event.eventType = eventType;
            event.payload = serializedPayload;
            event.occurredAt = Instant.now();
            event.idempotencyKey = idempotencyKey;
            event.attemptNumber = safeAttempt;
            event.backoffDelayMs = backoffDelayMs;
            event.failureReason = failureReason;
            if (state.policyVersionId != null) {
                event.policyVersionId = state.policyVersionId;
                Log.debugf("Event %s for workflow %s recorded with policyVersionId=%d",
                        eventType, workflowId, state.policyVersionId);
            }

            // 持久化事件
            event.persist();

            // 更新 workflow 状态
            state.lastEventSeq = workflowSeq;
            state.status = deriveStatusFromEvent(eventType);
            state.persist();

            // 发送 PostgreSQL NOTIFY 通知调度器
            if (supportsNotify()) {
                // NOTIFY 不支持参数绑定，使用字符串格式化
                em.createNativeQuery(String.format("NOTIFY workflow_events, '%s'", workflowId))
                        .executeUpdate();
            }

            // Snapshot 自动保存：基于事件数或时间间隔（两者满足其一即触发）
            // P0-2 criterion 4: 支持事件间隔和时间间隔两种触发方式
            boolean eventIntervalReached = workflowSeq % snapshotInterval == 0;
            boolean timeIntervalReached = false;

            if (state.lastSnapshotAt != null) {
                long minutesSinceLastSnapshot = java.time.Duration.between(
                    state.lastSnapshotAt,
                    Instant.now()
                ).toMinutes();
                timeIntervalReached = minutesSinceLastSnapshot >= snapshotTimeIntervalMinutes;
            }

            if (snapshotEnabled && (eventIntervalReached || timeIntervalReached)) {
                try {
                    // 获取当前状态作为 snapshot（简化实现：保存事件序号和状态）
                    Map<String, Object> snapshotState = new java.util.HashMap<>();
                    snapshotState.put("lastEventSeq", workflowSeq);
                    snapshotState.put("status", state.status);
                    snapshotState.put("timestamp", Instant.now().toString());
                    snapshotState.put("retry_context", state.getRetryContext());
                    saveSnapshot(workflowId, workflowSeq, snapshotState);

                    String reason = eventIntervalReached ?
                        "event interval (" + snapshotInterval + " events)" :
                        "time interval (" + snapshotTimeIntervalMinutes + " minutes)";
                    Log.infof("Auto-saved snapshot for workflow %s at event seq %d (trigger: %s)",
                        workflowId, workflowSeq, reason);
                } catch (Exception snapEx) {
                    // Snapshot 失败不影响事件追加
                    Log.warnf(snapEx, "Failed to auto-save snapshot for workflow %s at seq %d", workflowId, workflowSeq);
                }
            }

            // CQRS 查询投影更新：异步更新 Read Model
            try {
                projectionBuilder.updateProjection(workflowId, eventType, workflowSeq, serializedPayload, state);
            } catch (Exception projEx) {
                // 投影更新失败不影响事件追加（最终一致性）
                Log.warnf(projEx, "Failed to update query projection for workflow %s, event %s", workflowId, eventType);
            }

            Log.debugf("Appended event %s (seq=%d, workflow-seq=%d) for workflow %s",
                    eventType, globalSeq, workflowSeq, workflowId);
            return workflowSeq;

        } catch (Exception e) {
            Log.errorf(e, "Failed to append event %s for workflow %s", eventType, workflowId);
            throw new RuntimeException("Failed to append event", e);
        }
    }

    /**
     * 获取 workflow 的事件历史
     *
     * @param workflowId workflow 唯一标识符
     * @param fromSeq 起始序列号（包含）
     * @return 事件列表，按序列号升序排列
     */
    @Override
    public List<WorkflowEvent> getEvents(String workflowId, long fromSeq) {
        UUID wfId = UUID.fromString(workflowId);
        List<WorkflowEventEntity> entities = WorkflowEventEntity.findByWorkflowId(wfId, fromSeq);

        return entities.stream()
                .map(this::toWorkflowEvent)
                .toList();
    }

    /**
     * 获取 workflow 当前状态
     *
     * @param workflowId workflow 唯一标识符
     * @return workflow 状态，如果不存在则返回 empty
     */
    @Override
    public Optional<WorkflowState> getState(String workflowId) {
        UUID wfId = UUID.fromString(workflowId);
        return WorkflowStateEntity.findByWorkflowId(wfId)
                .map(this::toWorkflowState);
    }

    /**
     * 保存 workflow 状态快照
     *
     * @param workflowId workflow 唯一标识符
     * @param eventSeq 快照对应的事件序列号
     * @param state 序列化后的状态数据
     */
    @Transactional
    @Override
    @SuppressWarnings({"deprecation", "unchecked"}) // getOrCreate(UUID) - tenantId not in interface; Map cast is safe
    public void saveSnapshot(String workflowId, long eventSeq, Object state) {
        try {
            UUID wfId = UUID.fromString(workflowId);
            WorkflowStateEntity entity = WorkflowStateEntity.getOrCreate(wfId);

            Object snapshotPayload = state;
            if (state instanceof Map<?, ?> stateMap) {
                Map<String, Object> merged = new java.util.HashMap<>((Map<String, Object>) stateMap);
                merged.putIfAbsent("retry_context", entity.getRetryContext());
                snapshotPayload = merged;
            }

            entity.snapshot = serializePayload(snapshotPayload);
            entity.snapshotSeq = eventSeq;
            entity.lastSnapshotAt = Instant.now();  // P0-2 criterion 4: 更新快照时间戳

            // P0-7 Task 6: 持久化 DeterminismSnapshot 到 clockTimes 字段
            DeterminismContext context = workflowRuntime.getDeterminismContext();
            if (context != null) {
                String clockTimesJson = PostgresWorkflowRuntime.serializeDeterminismSnapshot(context);
                if (clockTimesJson != null) {
                    entity.clockTimes = clockTimesJson;
                }
            }

            entity.persist();

            Log.debugf("Saved snapshot for workflow %s at event seq %d", workflowId, eventSeq);

        } catch (Exception e) {
            Log.errorf(e, "Failed to save snapshot for workflow %s", workflowId);
            throw new RuntimeException("Failed to save snapshot", e);
        }
    }

    /**
     * 获取最近的快照
     *
     * @param workflowId workflow 唯一标识符
     * @return 快照数据，如果不存在则返回 empty
     */
    @Override
    public Optional<WorkflowSnapshot> getLatestSnapshot(String workflowId) {
        UUID wfId = UUID.fromString(workflowId);
        return WorkflowStateEntity.findByWorkflowId(wfId)
                .filter(s -> s.snapshot != null && s.snapshotSeq != null)
                .map(this::toWorkflowSnapshot);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 基于 workflow ID + 事件类型 + payload 哈希 生成幂等键，确保 replay 稳定性。
     */
    private String generateIdempotencyKey(String workflowId, String eventType, String payloadJson,
                                          Integer attemptNumber, Long backoffDelayMs, String failureReason) {
        boolean hasRetryMetadata = (attemptNumber != null && attemptNumber != 1)
                || backoffDelayMs != null
                || (failureReason != null && !failureReason.isBlank());

        StringBuilder hashInput = new StringBuilder(payloadJson != null ? payloadJson : "");
        if (hasRetryMetadata) {
            hashInput.append("|attempt=").append(attemptNumber != null ? attemptNumber : 1);
            if (backoffDelayMs != null) {
                hashInput.append("|backoff=").append(backoffDelayMs);
            }
            if (failureReason != null) {
                hashInput.append("|failure=").append(DigestUtils.sha256Hex(failureReason));
            }
        }

        String payloadHash = DigestUtils.sha256Hex(hashInput.toString());
        return String.format(
                "%s:%s:%s",
                workflowId,
                eventType,
                payloadHash.substring(0, Math.min(16, payloadHash.length()))
        );
    }

    /**
     * 序列化负载数据为 JSON 字符串
     */
    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }

    /**
     * 反序列化 JSON 字符串为对象
     */
    private Object deserializePayload(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize payload", e);
        }
    }

    /**
     * 从事件类型推导 workflow 状态
     *
     * 简化映射：
     * - WorkflowStarted -> RUNNING
     * - WorkflowCompleted -> COMPLETED
     * - WorkflowFailed -> FAILED
     * - CompensationScheduled -> COMPENSATING
     * - 其他 -> RUNNING
     */
    private String deriveStatusFromEvent(String eventType) {
        return switch (eventType) {
            case "WorkflowStarted" -> "RUNNING";
            case "WorkflowCompleted" -> "COMPLETED";
            case "WorkflowFailed" -> "FAILED";
            case "CompensationScheduled" -> "COMPENSATING";
            case "CompensationCompleted" -> "COMPENSATED";
            case "CompensationFailed" -> "COMPENSATION_FAILED";
            case "WorkflowTerminated" -> "TERMINATED";
            default -> "RUNNING";
        };
    }

    /**
     * 转换实体为 WorkflowEvent
     */
    private WorkflowEvent toWorkflowEvent(WorkflowEventEntity entity) {
        return new WorkflowEvent(
                entity.sequence,
                entity.workflowId.toString(),
                entity.eventType,
                deserializePayload(entity.payload),
                entity.occurredAt,
                entity.attemptNumber != null ? entity.attemptNumber : 1,
                entity.backoffDelayMs,
                entity.failureReason
        );
    }

    /**
     * 转换实体为 WorkflowState
     */
    private WorkflowState toWorkflowState(WorkflowStateEntity entity) {
        return new WorkflowState(
                entity.workflowId.toString(),
                WorkflowState.Status.valueOf(entity.status),
                entity.lastEventSeq,
                entity.result != null ? deserializePayload(entity.result) : null,
                entity.snapshot != null ? deserializePayload(entity.snapshot) : null,
                entity.snapshotSeq,
                entity.createdAt,
                entity.updatedAt
        );
    }

    /**
     * 转换实体为 WorkflowSnapshot
     */
    private WorkflowSnapshot toWorkflowSnapshot(WorkflowStateEntity entity) {
        return new WorkflowSnapshot(
                entity.workflowId.toString(),
                entity.snapshotSeq,
                deserializePayload(entity.snapshot)
        );
    }

    private boolean supportsNotify() {
        return !"h2".equalsIgnoreCase(dbKind);
    }

    private boolean isH2Database() {
        return "h2".equalsIgnoreCase(dbKind);
    }

    /**
     * 获取 workflow_events.seq 的下一个值
     *
     * 通过数据库序列生成器保证全局递增且线程安全，避免 getLastSequence()+1 带来的并发冲突。
     */
    private long nextSequenceValue() {
        String sql = "SELECT nextval(pg_get_serial_sequence('workflow_events', 'seq'))";
        if ("h2".equalsIgnoreCase(dbKind)) {
            ensureH2Sequence();
            sql = "SELECT nextval('workflow_events_seq_seq')";
        }
        Number value = (Number) em.createNativeQuery(sql).getSingleResult();
        return value.longValue();
    }

    /**
     * H2 环境下显式创建 workflow_events 序列，避免并发测试中重复定义
     */
    private void ensureH2Sequence() {
        em.createNativeQuery("CREATE SEQUENCE IF NOT EXISTS workflow_events_seq_seq START WITH 1")
                .executeUpdate();
    }
}
