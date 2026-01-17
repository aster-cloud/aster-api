package io.aster.workflow;

import aster.runtime.workflow.InMemoryWorkflowRuntime;
import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.service.PolicyStorageService;
import io.aster.policy.service.PolicyStorageService.PolicyDocument;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 非确定性来源修复验证
 */
class NonDeterminismSourceTest {

    @Test
    void testPolicyStorageUuidReplay() throws Exception {
        PolicyStorageService service = new PolicyStorageService();
        PostgresWorkflowRuntime runtime = Mockito.mock(PostgresWorkflowRuntime.class);
        setField(service, "workflowRuntime", runtime);

        DeterminismContext recording = new DeterminismContext();
        Mockito.when(runtime.getDeterminismContext()).thenReturn(recording);

        PolicyDocument first = service.createPolicy(
                "tenant-a",
                new PolicyDocument(null, "Policy A", Collections.emptyMap(), Collections.emptyMap())
        );
        String recordedId = first.getId();
        Assertions.assertThat(recordedId).isNotBlank();

        DeterminismContext replay = new DeterminismContext();
        replay.uuid().enterReplayMode(recording.uuid().getRecordedUuids());
        Mockito.reset(runtime);
        Mockito.when(runtime.getDeterminismContext()).thenReturn(replay);

        PolicyDocument replayDoc = service.createPolicy(
                "tenant-a",
                new PolicyDocument(null, "Policy B", Collections.emptyMap(), Collections.emptyMap())
        );

        Assertions.assertThat(replayDoc.getId()).isEqualTo(recordedId);
    }

    @Test
    void testIdempotencyKeyDeterministic() throws Exception {
        PostgresEventStore store = new PostgresEventStore();
        Method method = PostgresEventStore.class.getDeclaredMethod(
                "generateIdempotencyKey", String.class, String.class, String.class,
                Integer.class, Long.class, String.class);
        method.setAccessible(true);

        String payload = "{\"result\":\"ok\"}";
        String key1 = (String) method.invoke(store, "wf-1", "WorkflowStarted", payload, null, null, null);
        String key2 = (String) method.invoke(store, "wf-1", "WorkflowStarted", payload, null, null, null);
        String key3 = (String) method.invoke(store, "wf-1", "WorkflowStarted", payload + "x", null, null, null);

        Assertions.assertThat(key1).isEqualTo(key2);
        Assertions.assertThat(key3).isNotEqualTo(key1);
    }

    @Test
    void testInMemoryRuntimeDeterminism() {
        InMemoryWorkflowRuntime runtime = new InMemoryWorkflowRuntime();
        DeterminismContext context = runtime.getDeterminismContext();

        Assertions.assertThat(context).isNotNull();
        Assertions.assertThat(runtime.getDeterminismContext().clock()).isSameAs(context.clock());

        context.clock().now();
        Assertions.assertThat(context.clock().getRecordedTimes()).isNotEmpty();
    }

    @Test
    void testNoRemainingNonDeterministicSources() throws Exception {
        Map<String, List<Integer>> uuidMatches = scanPattern(
                "UUID.randomUUID",
                Paths.get("src/main/java"),
                Paths.get("../aster-runtime/src/main/java")
        );
        // 允许的 UUID.randomUUID 使用白名单：
        // - PolicyStorageService: 生成策略文档 ID (DeterminismContext 可重放)
        // - WorkflowSchedulerService: 生成工作流实例 ID (DeterminismContext 可重放)
        // - TimerSchedulerService: 生成定时器 ID (业务主键，随机性可接受)
        Assertions.assertThat(uuidMatches.keySet())
                .containsExactlyInAnyOrder(
                        "src/main/java/io/aster/policy/service/PolicyStorageService.java",
                        "src/main/java/io/aster/workflow/WorkflowSchedulerService.java",
                        "src/main/java/io/aster/workflow/TimerSchedulerService.java"
                );

        Map<String, List<Integer>> nanoMatches = scanPattern(
                "System.nanoTime",
                Paths.get("src/main/java"),
                Paths.get("../aster-runtime/src/main/java")
        );
        Assertions.assertThat(nanoMatches.keySet())
                .containsExactly("src/main/java/io/aster/policy/api/PolicyEvaluationService.java");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Map<String, List<Integer>> scanPattern(String needle, Path... roots) throws IOException {
        Map<String, List<Integer>> matches = new LinkedHashMap<>();
        Path moduleRoot = Paths.get("").toAbsolutePath();
        for (Path root : roots) {
            Path absoluteRoot = moduleRoot.resolve(root).normalize();
            if (!Files.exists(absoluteRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(absoluteRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> path.toString().contains("src/main/java"))
                        .forEach(path -> collectMatches(needle, matches, moduleRoot, path));
            }
        }
        return matches;
    }

    private static void collectMatches(String needle,
                                       Map<String, List<Integer>> matches,
                                       Path moduleRoot,
                                       Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            List<Integer> hitLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) {
                    hitLines.add(i + 1);
                }
            }
            if (!hitLines.isEmpty()) {
                String relative = moduleRoot.relativize(file).toString().replace('\\', '/');
                matches.put(relative, hitLines);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法读取文件: " + file, e);
        }
    }

    private static final class SamplePolicy {
        static String echo(String value) {
            return value;
        }
    }
}
