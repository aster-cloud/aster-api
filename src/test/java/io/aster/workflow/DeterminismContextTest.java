package io.aster.workflow;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 验证 DeterminismContext 在多线程环境中的隔离性，确保 ThreadLocal 不泄漏状态。
 */
class DeterminismContextTest {

    /**
     * 审计 runtime#19：WorkflowSchedulerService.processWorkflow 在池化线程上把 per-workflow
     * 的 uuid()/random() 绑定到静态 current() ThreadLocal（并在 finally 恢复）。本测试钉住该
     * 绑定/恢复语义——两个 workflow 复用同一线程时，各自执行期间 current() 必须是自己的实例，
     * 结束后精确恢复，杜绝跨 workflow 串扰。
     */
    @Test
    void testPerWorkflowBindingRestoresOnSameThread() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1); // 强制线程复用
        try {
            CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
                ReplayDeterministicUuid threadDefault = ReplayDeterministicUuid.current();

                // workflow A：绑定 A 的实例（模拟 processWorkflow 的 setCurrent）
                DeterminismContext ctxA = new DeterminismContext();
                ReplayDeterministicUuid prevA = ReplayDeterministicUuid.current();
                ReplayDeterministicUuid.setCurrent(ctxA.uuid());
                boolean aBound = ReplayDeterministicUuid.current() == ctxA.uuid();
                ReplayDeterministicUuid.setCurrent(prevA); // 恢复（finally）
                boolean aRestored = ReplayDeterministicUuid.current() == threadDefault;

                // workflow B（同一线程复用）：绑定 B 的实例——不得看到 A 的残留
                DeterminismContext ctxB = new DeterminismContext();
                ReplayDeterministicUuid prevB = ReplayDeterministicUuid.current();
                boolean bDoesNotSeeA = prevB != ctxA.uuid();
                ReplayDeterministicUuid.setCurrent(ctxB.uuid());
                boolean bBound = ReplayDeterministicUuid.current() == ctxB.uuid();
                ReplayDeterministicUuid.setCurrent(prevB);

                return aBound && aRestored && bDoesNotSeeA && bBound;
            }, executor);

            assertSame(Boolean.TRUE, result.get(5, TimeUnit.SECONDS),
                "per-workflow 绑定应在执行期生效、结束后恢复，且线程复用无跨 workflow 串扰");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testThreadLocalIsolation() throws Exception {
        // 使用固定线程池模拟并发 workflow 执行
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                DeterminismContext context = new DeterminismContext();
                return context.uuid().randomUUID().toString();
            }, executor);

            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
                DeterminismContext context = new DeterminismContext();
                return context.uuid().randomUUID().toString();
            }, executor);

            String uuid1 = f1.get(5, TimeUnit.SECONDS);
            String uuid2 = f2.get(5, TimeUnit.SECONDS);

            assertNotEquals(uuid1, uuid2, "不同线程应各自获得独立 DeterminismContext");
        } finally {
            executor.shutdownNow();
        }
    }
}
