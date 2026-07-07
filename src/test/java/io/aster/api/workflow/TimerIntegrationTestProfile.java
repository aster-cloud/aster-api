package io.aster.api.workflow;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;
import java.util.Map;

/**
 * Timer 集成测试专用 Profile，使用 PostgreSQL Testcontainers
 */
public class TimerIntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
            // 启用 scheduler 用于 timer 轮询
            Map.entry("quarkus.scheduler.enabled", "true"),
            // 但只需要 timer poller——关掉其余后台调度器（异常检测/清理/快照对账等），
            // 避免它们在本 IT 里陪跑污染断言或与并行测试竞争（issue #116）。
            // timer poller（TimerSchedulerService.pollExpiredTimers）不挂 skip 谓词，仍会触发。
            Map.entry("aster.scheduler.background.enabled", "false"),
            // 启用 workflow 调度器后台轮询（timer 触发后需要 poller 推进 workflow 状态）
            Map.entry("workflow.scheduler.polling.enabled", "true"),
            // 设置日志级别
            Map.entry("quarkus.log.category.\"io.aster.api.workflow\".level", "DEBUG")
        );
    }

    @Override
    public List<TestResourceEntry> testResources() {
        // 使用 PostgresTestResource 提供真实数据库
        return List.of(new TestResourceEntry(io.aster.test.PostgresTestResource.class));
    }
}
