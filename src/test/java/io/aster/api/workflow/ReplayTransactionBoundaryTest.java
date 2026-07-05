package io.aster.api.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.smallrye.mutiny.Uni;
import jakarta.transaction.Transactional;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #57 回归守卫（纯单元测试，无需 Docker/CDI）。
 *
 * <p>修复前：{@code replayWorkflow} 同时标注 {@code @Transactional} 并返回一个
 * *延迟体* 的 {@code Uni} —— JTA 事务在方法返回 Uni 对象时即关闭，而真正的持久化
 * 工作在订阅时才执行，因此运行在事务/Session 之外。这是 #57 要修复的核心 bug 类型。
 *
 * <p>本测试通过反射锁定正确的边界结构，防止回归：
 * <ul>
 *   <li>{@code replayWorkflow}（返回 Uni 的反应式包装器）<b>不得</b>标注 @Transactional；</li>
 *   <li>同步阻塞辅助方法 {@code prepareReplay} / {@code reloadState} <b>必须</b>标注 @Transactional；</li>
 *   <li>{@code processWorkflow}（同步阻塞）<b>必须</b>标注 @Transactional。</li>
 * </ul>
 *
 * <p>详见 docs/persistence-model.md。
 */
class ReplayTransactionBoundaryTest {

    @Test
    @DisplayName("replayWorkflow 返回 Uni，因此不得标注 @Transactional（事务无法覆盖延迟体）")
    void replayWorkflowMustNotBeTransactional() throws NoSuchMethodException {
        Method replay = WorkflowSchedulerService.class.getMethod("replayWorkflow", UUID.class);

        // 它确实返回 Uni（反应式形状）
        assertTrue(Uni.class.isAssignableFrom(replay.getReturnType()),
            "replayWorkflow 应返回 Uni");

        // 关键不变量：返回 Uni 的方法不能挂 @Transactional —— JTA 事务会在生成
        // Uni 对象时关闭，而真正的工作在订阅时才执行（见 docs/persistence-model.md）。
        assertFalse(replay.isAnnotationPresent(Transactional.class),
            "replayWorkflow 返回 Uni，绝不能标注 @Transactional：事务会在延迟体执行前关闭");
    }

    @Test
    @DisplayName("同步阻塞辅助方法必须标注 @Transactional 以正确界定事务边界")
    void syncHelpersMustBeTransactional() throws NoSuchMethodException {
        Method prepareReplay = WorkflowSchedulerService.class.getMethod("prepareReplay", UUID.class);
        Method reloadState = WorkflowSchedulerService.class.getMethod("reloadState", UUID.class);
        Method processWorkflow = WorkflowSchedulerService.class.getMethod("processWorkflow", String.class);

        assertTrue(prepareReplay.isAnnotationPresent(Transactional.class),
            "prepareReplay 是同步阻塞持久化，必须 @Transactional");
        assertTrue(reloadState.isAnnotationPresent(Transactional.class),
            "reloadState 是同步阻塞查询，必须 @Transactional");
        assertTrue(processWorkflow.isAnnotationPresent(Transactional.class),
            "processWorkflow 是同步阻塞持久化，必须 @Transactional");

        // 这些同步辅助方法不应返回 Uni（它们属于阻塞模型）
        assertFalse(Uni.class.isAssignableFrom(prepareReplay.getReturnType()),
            "prepareReplay 属于阻塞模型，不应返回 Uni");
        assertFalse(Uni.class.isAssignableFrom(reloadState.getReturnType()),
            "reloadState 属于阻塞模型，不应返回 Uni");
    }
}
