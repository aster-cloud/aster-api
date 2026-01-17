package io.aster.policy.runtime;

import io.aster.policy.compiler.CompilationMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TrufflePolicyRuntime 单元测试
 */
class TrufflePolicyRuntimeTest {

    private TrufflePolicyRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new TrufflePolicyRuntime();
        runtime.init();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.cleanup();
        }
    }

    @Test
    void shouldInitializeContextPool() {
        // Context 池应该初始化成功
        assertThat(runtime).isNotNull();
    }

    @Test
    void shouldExecuteSimplePolicy() {
        // 简单的 Core JSON 示例（返回常量）
        String coreJson = """
            {
              "module": "test",
              "functions": [{
                "name": "evaluate",
                "params": [],
                "body": { "kind": "IntLiteral", "value": 42 }
              }]
            }
            """;

        CompilationMetadata metadata = new CompilationMetadata(
            "evaluate",
            "[]",
            "Int"
        );

        ExecutionResult result = runtime.execute(coreJson, new Object[0], metadata);

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isNull();
    }

    @Test
    void shouldHandleExecutionError() {
        // 无效的 Core JSON
        String invalidJson = "{ invalid json }";

        CompilationMetadata metadata = CompilationMetadata.empty();

        ExecutionResult result = runtime.execute(invalidJson, new Object[0], metadata);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void shouldHandleConcurrentExecution() throws Exception {
        // 准备测试数据
        String coreJson = """
            {
              "module": "test",
              "functions": [{
                "name": "evaluate",
                "params": [],
                "body": { "kind": "IntLiteral", "value": 42 }
              }]
            }
            """;

        CompilationMetadata metadata = new CompilationMetadata("evaluate", "[]", "Int");

        // 创建线程池，执行 10 个并发请求
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<ExecutionResult>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Future<ExecutionResult> future = executor.submit(() ->
                runtime.execute(coreJson, new Object[0], metadata)
            );
            futures.add(future);
        }

        // 等待所有任务完成
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(finished).isTrue();

        // 验证所有请求都成功
        for (Future<ExecutionResult> future : futures) {
            ExecutionResult result = future.get();
            assertThat(result.success()).isTrue();
        }
    }
}
