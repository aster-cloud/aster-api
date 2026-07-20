package io.aster.api.workflow;

import java.util.HashMap;
import java.util.Map;

/**
 * 崩溃恢复测试配置
 *
 * 继承 TimerIntegrationTestProfile 以复用:
 * - PostgresTestResource (Testcontainers)
 * - DEBUG 日志级别
 *
 * ★关键覆盖：关闭 Quarkus Scheduler（quarkus.scheduler.enabled=false）。
 * 崩溃恢复测试改由测试线程**同步**调用公开的 pollExpiredTimers() 驱动所有 timer 恢复，
 * 若保留 @Scheduled(1s) 后台轮询线程，它会与同步调用竞争同一 timer（生产侧无真正的乐观锁，
 * TimerSchedulerService 只是普通 status 写入），导致偶发把 timer 抢跑成 COMPLETED retry=1
 * 而使抛错永不落地。关闭后台轮询后测试完全确定，不再依赖时序运气。
 *
 * 仅覆盖本 profile 自身的 config map，不修改共享父类（其它测试仍需 scheduler 开启）。
 */
public class CrashRecoveryTestProfile extends TimerIntegrationTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        // 关闭后台调度器：本测试类全部改为同步驱动 pollExpiredTimers()，杜绝后台线程竞态。
        overrides.put("quarkus.scheduler.enabled", "false");
        return overrides;
    }
}
