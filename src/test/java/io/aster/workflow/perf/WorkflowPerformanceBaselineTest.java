package io.aster.workflow.perf;

import aster.runtime.workflow.ExecutionHandle;
import aster.runtime.workflow.WorkflowMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aster.perf.PerfStats;
import io.aster.perf.SystemMetrics;
import aster.runtime.workflow.WorkflowEvent;
import io.aster.workflow.PostgresEventStore;
import io.aster.workflow.PostgresWorkflowRuntime;
import io.aster.workflow.WorkflowEventEntity;
import io.aster.workflow.WorkflowSchedulerService;
import io.aster.workflow.WorkflowStateEntity;
import io.aster.workflow.WorkflowTimerEntity;
import io.aster.policy.tenant.TenantContext;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Workflow 执行端到端性能基准：基于现有调度/事件存储管线，批量构造可补偿 workflow 并测量延迟、吞吐与 GC。
 */
@QuarkusTest
public class WorkflowPerformanceBaselineTest {

    private static final int DEFAULT_TOTAL_WORKFLOWS = Integer.getInteger("workflow.baseline.total", 60);
    private static final int DEFAULT_CONCURRENCY = Integer.getInteger("workflow.baseline.concurrency", 8);

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    WorkflowSchedulerService schedulerService;

    @Inject
    PostgresEventStore eventStore;

    @Inject
    RequestContextController requestContextController;

    @Inject
    TenantContext tenantContext;

    @BeforeEach
    @Transactional
    void cleanTables() {
        WorkflowEventEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
        WorkflowTimerEntity.deleteAll();
    }

    @Test
    void captureWorkflowExecutionBaseline() throws Exception {
        WorkflowBaselineReport report = executeBenchmark(DEFAULT_TOTAL_WORKFLOWS, DEFAULT_CONCURRENCY);
        writeReport(report, "workflow-baseline.json");
        Assertions.assertThat(report.totalWorkflows()).isEqualTo(DEFAULT_TOTAL_WORKFLOWS);
    }

    private WorkflowBaselineReport executeBenchmark(int totalWorkflows, int concurrencyLevel) throws Exception {
        List<Double> endToEndSamples = Collections.synchronizedList(new ArrayList<>());
        List<Double> schedulerSamples = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);
        CountDownLatch latch = new CountDownLatch(totalWorkflows);
        AtomicInteger successCounter = new AtomicInteger();

        SystemMetrics.MemorySnapshot memoryBefore = SystemMetrics.captureMemory();
        List<SystemMetrics.GcSnapshot> gcBefore = SystemMetrics.captureGcSnapshots();
        long startNs = System.nanoTime();

        for (int i = 0; i < totalWorkflows; i++) {
            executor.submit(() -> {
                requestContextController.activate();
                try {
                    tenantContext.setCurrentTenant("test-tenant");
                    WorkflowRunSample sample = runSingleWorkflow();
                    endToEndSamples.add(sample.endToEndMs());
                    schedulerSamples.add(sample.schedulerMs());
                    successCounter.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    try {
                        requestContextController.deactivate();
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        boolean completed = latch.await(180, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        long durationNs = System.nanoTime() - startNs;
        SystemMetrics.MemorySnapshot memoryAfter = SystemMetrics.captureMemory();
        List<SystemMetrics.GcDelta> gcDelta = SystemMetrics.diffGc(gcBefore, SystemMetrics.captureGcSnapshots());

        if (!completed) {
            throw new IllegalStateException("Workflow baseline 执行超时");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Workflow baseline 运行失败", errors.get(0));
        }

        double throughput = successCounter.get() / (durationNs / 1_000_000_000.0);
        PerfStats.Summary endToEnd = PerfStats.summarize("workflow_end_to_end", endToEndSamples);
        PerfStats.Summary scheduler = PerfStats.summarize("workflow_scheduler", schedulerSamples);

        return new WorkflowBaselineReport(
            "workflow_execution",
            successCounter.get(),
            concurrencyLevel,
            throughput,
            endToEnd,
            scheduler,
            SystemMetrics.window(memoryBefore, memoryAfter),
            gcDelta,
            new ArrayList<>(endToEndSamples),
            new ArrayList<>(schedulerSamples)
        );
    }

    private WorkflowRunSample runSingleWorkflow() throws Exception {
        String workflowId = UUID.randomUUID().toString();
        WorkflowMetadata metadata = new WorkflowMetadata();
        metadata.set("baseline", "workflow");
        ExecutionHandle handle = workflowRuntime.schedule(workflowId, "baseline-" + workflowId, metadata);

        long startNs = System.nanoTime();
        appendStepStarted(workflowId, "ingest", List.of());
        appendStepCompleted(workflowId, "ingest", List.of(), Map.of("status", "ok"), false);

        appendStepStarted(workflowId, "evaluate", List.of("ingest"));
        appendStepCompleted(workflowId, "evaluate", List.of("ingest"), Map.of("score", 0.87), true);

        appendStepStarted(workflowId, "persist", List.of("evaluate"));
        appendStepCompleted(workflowId, "persist", List.of("evaluate"), Map.of("stored", true), false);
        appendWorkflowCompleted(workflowId, Map.of("result", "baseline-ok"));

        long schedulerNs = System.nanoTime();
        schedulerService.processWorkflow(workflowId);
        handle.getResult().get(5, TimeUnit.SECONDS);
        long endNs = System.nanoTime();

        return new WorkflowRunSample(
            (endNs - startNs) / 1_000_000.0,
            (endNs - schedulerNs) / 1_000_000.0
        );
    }

    private void appendStepStarted(String workflowId, String stepId, List<String> dependencies) {
        eventStore.appendEvent(
            workflowId,
            WorkflowEvent.Type.STEP_STARTED,
            Map.of(
                "stepId", stepId,
                "dependencies", new ArrayList<>(dependencies),
                "status", "STARTED",
                "startedAt", Instant.now().toString()
            )
        );
    }

    private void appendStepCompleted(String workflowId,
                                     String stepId,
                                     List<String> dependencies,
                                     Object result,
                                     boolean hasCompensation) {
        eventStore.appendEvent(
            workflowId,
            WorkflowEvent.Type.STEP_COMPLETED,
            Map.of(
                "stepId", stepId,
                "dependencies", new ArrayList<>(dependencies),
                "status", "COMPLETED",
                "completedAt", Instant.now().toString(),
                "hasCompensation", hasCompensation,
                "result", result
            )
        );
    }

    private void appendWorkflowCompleted(String workflowId, Object payload) {
        eventStore.appendEvent(workflowId, WorkflowEvent.Type.WORKFLOW_COMPLETED, payload);
    }

    private void writeReport(WorkflowBaselineReport report, String fileName) throws IOException {
        Path reportDir = Path.of("build", "perf");
        Files.createDirectories(reportDir);
        Path reportPath = reportDir.resolve(fileName);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(reportPath.toFile(), report);
        System.out.printf("[workflow-baseline] 写入 %s%n", reportPath.toAbsolutePath());
    }

    private record WorkflowRunSample(double endToEndMs, double schedulerMs) {
    }

    public record WorkflowBaselineReport(
        String component,
        int totalWorkflows,
        int concurrencyLevel,
        double throughputPerSecond,
        PerfStats.Summary endToEnd,
        PerfStats.Summary scheduler,
        SystemMetrics.MemoryWindow memory,
        List<SystemMetrics.GcDelta> gc,
        List<Double> endToEndSamples,
        List<Double> schedulerSamples
    ) {
    }
}
