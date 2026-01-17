package io.aster.workflow;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-2 Criterion 4 验证测试：快照时间间隔配置
 *
 * 测试目标：
 * 1. 验证事件间隔触发快照（现有功能）
 * 2. 验证时间间隔触发快照（新功能）
 * 3. 验证 last_snapshot_at 字段正确更新
 * 4. 验证两种触发方式的 OR 逻辑
 *
 * 验收标准：P0-2 criterion 4 - 快照间隔可配置（时间/事件数）
 */
@QuarkusTest
public class SnapshotTimeIntervalTest {

    @Inject
    PostgresEventStore eventStore;

    /**
     * 测试 1: 验证事件间隔触发快照（基线测试）
     *
     * 测试流程:
     * 1. 创建 workflow 并追加 100 个 events（达到默认间隔）
     * 2. 验证在第 100 个 event 时自动保存快照
     * 3. 验证 snapshotSeq = 100
     */
    @Test
    @Transactional
    public void testEventIntervalSnapshot() {
        String workflowId = UUID.randomUUID().toString();

        // 追加 99 个 events，不应触发快照
        for (int i = 1; i < 100; i++) {
            eventStore.appendEvent(workflowId, "TaskCompleted",
                String.format("{\"taskId\": \"task-%d\", \"result\": \"success\"}", i));
        }

        // 验证没有快照
        var snapshotBefore = eventStore.getLatestSnapshot(workflowId);
        assertTrue(snapshotBefore.isEmpty(), "第 99 个 event 后不应有快照");

        // 追加第 100 个 event，应触发快照
        eventStore.appendEvent(workflowId, "TaskCompleted",
            "{\"taskId\": \"task-100\", \"result\": \"success\"}");

        // 验证快照已创建
        var snapshotAfter = eventStore.getLatestSnapshot(workflowId);
        assertTrue(snapshotAfter.isPresent(), "第 100 个 event 后应自动保存快照");
        assertEquals(100L, snapshotAfter.get().getEventSeq(),
            "快照序列号应为 100");

        // 验证 workflow_state.last_snapshot_at 已更新
        var state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId));
        assertTrue(state.isPresent(), "workflow_state 应存在");
        assertNotNull(state.get().lastSnapshotAt, "last_snapshot_at 应已设置");
    }

    /**
     * 测试 2: 验证时间间隔触发快照（新功能）
     *
     * 测试流程:
     * 1. 创建 workflow 并保存初始快照（t0）
     * 2. 手动将 last_snapshot_at 设置为 6 分钟前（超过默认 5 分钟间隔）
     * 3. 追加 1 个 event（远未达到 100 个事件间隔）
     * 4. 验证快照被时间间隔触发
     */
    @Test
    @Transactional
    public void testTimeIntervalSnapshot() {
        String workflowId = UUID.randomUUID().toString();

        // 创建初始 event，触发 workflow 创建
        eventStore.appendEvent(workflowId, "WorkflowStarted",
            "{\"type\": \"OrderWorkflow\", \"orderId\": \"12345\"}");

        // 获取 workflow_state
        var stateOpt = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId));
        assertTrue(stateOpt.isPresent(), "workflow_state 应存在");

        WorkflowStateEntity state = stateOpt.get();

        // 手动保存初始快照并设置 last_snapshot_at 为 6 分钟前
        Instant sixMinutesAgo = Instant.now().minus(6, ChronoUnit.MINUTES);
        state.lastSnapshotAt = sixMinutesAgo;
        state.snapshotSeq = 1L;
        state.snapshot = "{\"lastEventSeq\": 1, \"status\": \"RUNNING\"}";
        state.persist();

        // 追加第 2 个 event（远未达到 100 个事件间隔，但超过时间间隔）
        long seq = eventStore.appendEvent(workflowId, "TaskCompleted",
            "{\"taskId\": \"task-1\", \"result\": \"success\"}");

        assertEquals(2L, seq, "事件序列号应为 2");

        // 验证快照被时间间隔触发（而非事件间隔）
        var snapshot = eventStore.getLatestSnapshot(workflowId);
        assertTrue(snapshot.isPresent(), "快照应被时间间隔触发");
        assertEquals(2L, snapshot.get().getEventSeq(), "快照序列号应为 2（最新 event）");

        // 验证 last_snapshot_at 已更新为当前时间附近
        state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId)).get();
        assertNotNull(state.lastSnapshotAt, "last_snapshot_at 应已更新");

        long timeSinceSnapshot = ChronoUnit.SECONDS.between(
            state.lastSnapshotAt,
            Instant.now()
        );
        assertTrue(timeSinceSnapshot < 5,
            "last_snapshot_at 应在最近 5 秒内更新（实际: " + timeSinceSnapshot + "s）");
    }

    /**
     * 测试 3: 验证时间间隔未到时不触发快照
     *
     * 测试流程:
     * 1. 创建 workflow 并保存初始快照
     * 2. 追加少量 events（<100）且时间间隔未到（<5 分钟）
     * 3. 验证快照不被触发
     */
    @Test
    @Transactional
    public void testNoSnapshotWhenNeitherIntervalReached() {
        String workflowId = UUID.randomUUID().toString();

        // 创建初始 event
        eventStore.appendEvent(workflowId, "WorkflowStarted",
            "{\"type\": \"OrderWorkflow\"}");

        // 手动保存初始快照，last_snapshot_at 设置为 1 分钟前（未超过 5 分钟）
        var state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId)).get();
        state.lastSnapshotAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        state.snapshotSeq = 1L;
        state.snapshot = "{\"lastEventSeq\": 1, \"status\": \"RUNNING\"}";
        state.persist();

        // 追加少量 events（<100）
        for (int i = 2; i <= 10; i++) {
            eventStore.appendEvent(workflowId, "TaskCompleted",
                String.format("{\"taskId\": \"task-%d\"}", i));
        }

        // 验证快照序列号仍为 1（未触发新快照）
        var snapshot = eventStore.getLatestSnapshot(workflowId);
        assertTrue(snapshot.isPresent(), "初始快照应存在");
        assertEquals(1L, snapshot.get().getEventSeq(),
            "快照序列号应仍为 1（未触发新快照）");
    }

    /**
     * 测试 4: 验证 OR 逻辑（任一条件满足即触发）
     *
     * 测试流程:
     * 1. 测试事件间隔触发（时间未到但事件数到了）
     * 2. 测试时间间隔触发（事件数未到但时间到了）
     * 3. 两种方式应独立工作
     */
    @Test
    @Transactional
    public void testSnapshotOrLogic() {
        // Part A: 事件间隔触发（时间未到）
        String workflowId1 = UUID.randomUUID().toString();

        for (int i = 1; i <= 100; i++) {
            eventStore.appendEvent(workflowId1, "TaskCompleted",
                String.format("{\"taskId\": \"task-%d\"}", i));
        }

        var snapshot1 = eventStore.getLatestSnapshot(workflowId1);
        assertTrue(snapshot1.isPresent(), "事件间隔应触发快照");
        assertEquals(100L, snapshot1.get().getEventSeq());

        // Part B: 时间间隔触发（事件数未到）
        String workflowId2 = UUID.randomUUID().toString();

        eventStore.appendEvent(workflowId2, "WorkflowStarted", "{}");

        var state2 = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId2)).get();
        state2.lastSnapshotAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        state2.snapshotSeq = 1L;
        state2.snapshot = "{}";
        state2.persist();

        eventStore.appendEvent(workflowId2, "TaskCompleted", "{\"taskId\": \"task-1\"}");

        var snapshot2 = eventStore.getLatestSnapshot(workflowId2);
        assertTrue(snapshot2.isPresent(), "时间间隔应触发快照");
        assertEquals(2L, snapshot2.get().getEventSeq());
    }
}
