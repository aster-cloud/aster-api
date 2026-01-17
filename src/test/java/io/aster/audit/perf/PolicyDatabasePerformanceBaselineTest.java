package io.aster.audit.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aster.audit.dto.AnomalyReportDTO;
import io.aster.audit.dto.VersionUsageStatsDTO;
import io.aster.audit.service.PolicyAnalyticsService;
import io.aster.audit.service.PolicyAuditService;
import io.aster.common.dto.PagedResult;
import io.aster.perf.PerfStats;
import io.aster.perf.SystemMetrics;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.tenant.TenantContext;
import io.aster.workflow.WorkflowEventEntity;
import io.aster.workflow.WorkflowStateEntity;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 数据库查询性能基线：涵盖审计 REST（依赖 PolicyAuditService）与聚合分析（PolicyAnalyticsService）。
 */
@QuarkusTest
public class PolicyDatabasePerformanceBaselineTest {

    private static final int DEFAULT_WORKFLOW_ROWS = Integer.getInteger("db.baseline.workflowRows", 3000);
    private static final int DEFAULT_ITERATIONS = Integer.getInteger("db.baseline.iterations", 20);
    private static final String[] STATUS_FILTERS = new String[]{null, "COMPLETED", "FAILED"};

    @Inject
    PolicyAnalyticsService analyticsService;

    @Inject
    PolicyAuditService auditService;

    @Inject
    RequestContextController requestContextController;

    @Inject
    TenantContext tenantContext;

    private DatasetSummary dataset;

    @BeforeEach
    @Transactional
    void resetAndSeedDataset() {
        WorkflowEventEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
        PolicyVersion.deleteAll();
        dataset = seedDataset(DEFAULT_WORKFLOW_ROWS);
    }

    @Test
    void captureDatabaseQueryBaseline() throws IOException {
        requestContextController.activate();
        try {
            tenantContext.setCurrentTenant(dataset.tenants().get(0));
            DatabaseBaselineReport report = measureBaseline(DEFAULT_ITERATIONS);
            writeReport(report, "database-baseline.json");
            Assertions.assertThat(report.metrics().size()).isGreaterThanOrEqualTo(4);
        } finally {
            requestContextController.deactivate();
        }
    }

    private DatabaseBaselineReport measureBaseline(int iterations) {
        Map<String, PerfStats.Summary> metrics = new LinkedHashMap<>();
        Map<String, List<Double>> rawSamples = new LinkedHashMap<>();
        Map<String, Integer> resultSizes = new LinkedHashMap<>();

        SystemMetrics.MemorySnapshot memoryBefore = SystemMetrics.captureMemory();
        List<SystemMetrics.GcSnapshot> gcBefore = SystemMetrics.captureGcSnapshots();

        long versionId = dataset.policyVersionIds().get(0);
        AtomicInteger statusCursor = new AtomicInteger();
        MeasurementResult versionUsage = measureMetric(
            "audit_version_usage",
            iterations,
            () -> {
            },
            () -> {
                String status = STATUS_FILTERS[statusCursor.getAndIncrement() % STATUS_FILTERS.length];
                PagedResult<?> page = auditService.getVersionUsage(versionId, status, 0, 50);
                return page.items.size();
            }
        );
        putMetric("audit_version_usage", versionUsage, metrics, rawSamples, resultSizes);

        MeasurementResult timeline = measureMetric(
            "audit_version_timeline",
            iterations,
            () -> {
            },
            () -> {
                PagedResult<?> page = auditService.getVersionTimeline(
                    versionId,
                    dataset.from(),
                    dataset.to(),
                    0,
                    50
                );
                return page.items.size();
            }
        );
        putMetric("audit_version_timeline", timeline, metrics, rawSamples, resultSizes);

        MeasurementResult impact = measureMetric(
            "audit_version_impact",
            iterations,
            () -> {
            },
            () -> auditService.assessImpact(versionId).totalCount
        );
        putMetric("audit_version_impact", impact, metrics, rawSamples, resultSizes);

        String sampleWorkflowId = dataset.sampleWorkflowIds().get(0);
        MeasurementResult history = measureMetric(
            "audit_workflow_version_history",
            iterations,
            () -> {
            },
            () -> auditService.getWorkflowVersionHistory(UUID.fromString(sampleWorkflowId)).size()
        );
        putMetric("audit_workflow_version_history", history, metrics, rawSamples, resultSizes);

        AtomicInteger tenantCursor = new AtomicInteger();
        AtomicInteger usageStatsIteration = new AtomicInteger();
        MeasurementResult usageStats = measureMetric(
            "analytics_version_usage_stats",
            iterations,
            () -> {
            },
            () -> {
                // Phase 4.3: tenant 不再作为参数传递，由 TenantContext 自动获取
                // String tenant = dataset.tenants().get(tenantCursor.getAndIncrement() % dataset.tenants().size());
                Instant from = dataset.from().plusSeconds(usageStatsIteration.getAndIncrement());
                Instant to = dataset.to().minusSeconds(usageStatsIteration.get());
                List<VersionUsageStatsDTO> stats = analyticsService.getVersionUsageStats(
                    versionId,
                    "hour",
                    from,
                    to
                );
                return stats.size();
            }
        );
        putMetric("analytics_version_usage_stats", usageStats, metrics, rawSamples, resultSizes);

        MeasurementResult anomalyDetection = measureMetric(
            "analytics_detect_anomalies",
            iterations,
            () -> {
            },
            () -> {
                List<AnomalyReportDTO> reports = analyticsService.detectAnomalies(0.25, 7);
                return reports.size();
            }
        );
        putMetric("analytics_detect_anomalies", anomalyDetection, metrics, rawSamples, resultSizes);

        SystemMetrics.MemorySnapshot memoryAfter = SystemMetrics.captureMemory();
        List<SystemMetrics.GcDelta> gcDelta = SystemMetrics.diffGc(gcBefore, SystemMetrics.captureGcSnapshots());

        return new DatabaseBaselineReport(
            "database_queries",
            dataset,
            metrics,
            rawSamples,
            resultSizes,
            SystemMetrics.window(memoryBefore, memoryAfter),
            gcDelta
        );
    }

    private MeasurementResult measureMetric(String metricName,
                                            int iterations,
                                            Runnable beforeEach,
                                            Supplier<Integer> action) {
        List<Double> samples = new ArrayList<>();
        int lastSize = 0;
        for (int i = 0; i < iterations; i++) {
            if (beforeEach != null) {
                beforeEach.run();
            }
            long start = System.nanoTime();
            lastSize = action.get();
            long elapsed = System.nanoTime() - start;
            samples.add(elapsed / 1_000_000.0);
        }
        return new MeasurementResult(PerfStats.summarize(metricName, samples), samples, lastSize);
    }

    private void putMetric(String name,
                           MeasurementResult result,
                           Map<String, PerfStats.Summary> metrics,
                           Map<String, List<Double>> samples,
                           Map<String, Integer> resultSizes) {
        metrics.put(name, result.summary());
        samples.put(name, new ArrayList<>(result.samples()));
        resultSizes.put(name, result.lastResultSize());
    }

    private void writeReport(DatabaseBaselineReport report, String fileName) throws IOException {
        Path reportDir = Path.of("build", "perf");
        Files.createDirectories(reportDir);
        Path reportPath = reportDir.resolve(fileName);
        ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.writeValue(reportPath.toFile(), report);
        System.out.printf("[database-baseline] 写入 %s%n", reportPath.toAbsolutePath());
    }

    @Transactional
    DatasetSummary seedDataset(int workflowRows) {
        List<PolicyVersion> versions = new ArrayList<>();
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            PolicyVersion version = new PolicyVersion();
            version.policyId = "policy-" + i;
            version.version = 1_000L + i;
            version.moduleName = "aster.finance.sample" + i;
            version.functionName = "evaluate";
            version.content = "// baseline";
            version.active = true;
            version.createdAt = base;
            version.createdBy = "baseline";
            version.notes = "baseline";
            version.persist();
            versions.add(version);
        }

        Map<String, Long> statusCounts = new HashMap<>();
        List<String> tenants = List.of("tenant-alpha", "tenant-beta", "tenant-gamma");
        String[] statuses = {"COMPLETED", "FAILED", "RUNNING"};
        List<String> workflowIds = new ArrayList<>();

        for (int i = 0; i < workflowRows; i++) {
            WorkflowStateEntity state = new WorkflowStateEntity();
            state.workflowId = UUID.randomUUID();
            String status = statuses[i % statuses.length];
            state.status = status;
            state.lastEventSeq = 10L;
            state.policyVersionId = versions.get(i % versions.size()).id;
            state.tenantId = tenants.get(i % tenants.size());

            Instant started = base.plusSeconds(i * 30L);
            state.startedAt = started;
            if (!"RUNNING".equals(status)) {
                Instant completed = started.plus(Duration.ofMillis(80 + (i % 40)));
                state.completedAt = completed;
                state.durationMs = Duration.between(started, completed).toMillis();
            }
            state.policyActivatedAt = started.plusSeconds(5);
            state.persist();

            statusCounts.merge(status, 1L, Long::sum);
            if (workflowIds.size() < 50) {
                workflowIds.add(state.workflowId.toString());
            }
        }

        Instant from = base.minus(Duration.ofDays(1));
        Instant to = base.plusSeconds(workflowRows * 30L + 3600);

        List<Long> versionIds = versions.stream().map(v -> v.id).collect(Collectors.toList());
        return new DatasetSummary(
            workflowRows,
            versionIds,
            tenants,
            workflowIds,
            from,
            to,
            statusCounts
        );
    }

    private record MeasurementResult(
        PerfStats.Summary summary,
        List<Double> samples,
        int lastResultSize
    ) {
    }

    public record DatasetSummary(
        int totalWorkflows,
        List<Long> policyVersionIds,
        List<String> tenants,
        List<String> sampleWorkflowIds,
        Instant from,
        Instant to,
        Map<String, Long> statusCounts
    ) {
    }

    public record DatabaseBaselineReport(
        String component,
        DatasetSummary dataset,
        Map<String, PerfStats.Summary> metrics,
        Map<String, List<Double>> rawSamples,
        Map<String, Integer> resultSizes,
        SystemMetrics.MemoryWindow memory,
        List<SystemMetrics.GcDelta> gc
    ) {
    }
}
